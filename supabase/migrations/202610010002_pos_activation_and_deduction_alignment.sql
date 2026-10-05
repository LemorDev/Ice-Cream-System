-- Keep POS cash deductions and device replacement consistent with the IMS.
-- This follows the separate_revenue_deductions migration.

alter table public.revenue_deductions
  drop constraint if exists revenue_deductions_business_day_id_fkey;
alter table public.revenue_deductions
  add constraint revenue_deductions_business_day_id_fkey
  foreign key (business_day_id) references public.business_days(id) on delete cascade;
alter table public.revenue_deductions
  add column if not exists affects_profit boolean not null default true;

alter table public.devices
  add column if not exists activation_expires_at timestamptz,
  add column if not exists transfer_ready_at timestamptz;

create table if not exists public.pos_recovery_incidents (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  old_device_id uuid not null references public.devices(id),
  new_device_id uuid references public.devices(id),
  business_day_id uuid references public.business_days(id) on delete set null,
  business_date date,
  reason text not null,
  known_sales numeric(12,2) not null default 0,
  known_orders integer not null default 0,
  known_deductions numeric(12,2) not null default 0,
  known_profit_deductions numeric(12,2) not null default 0,
  known_cogs numeric(12,2) not null default 0,
  known_waste numeric(12,2) not null default 0,
  status text not null default 'pending' check (status in ('pending', 'activated', 'reviewed')),
  created_at timestamptz not null default now(),
  reviewed_at timestamptz,
  review_notes text
);
alter table public.pos_recovery_incidents
  add column if not exists new_device_id uuid references public.devices(id),
  add column if not exists business_day_id uuid references public.business_days(id) on delete set null,
  add column if not exists business_date date,
  add column if not exists reason text,
  add column if not exists known_sales numeric(12,2) not null default 0,
  add column if not exists known_orders integer not null default 0,
  add column if not exists known_deductions numeric(12,2) not null default 0,
  add column if not exists known_profit_deductions numeric(12,2) not null default 0,
  add column if not exists known_cogs numeric(12,2) not null default 0,
  add column if not exists known_waste numeric(12,2) not null default 0,
  add column if not exists status text not null default 'pending',
  add column if not exists created_at timestamptz not null default now(),
  add column if not exists reviewed_at timestamptz,
  add column if not exists review_notes text;
create index if not exists pos_recovery_incidents_stall_idx
  on public.pos_recovery_incidents(stall_id, created_at desc);
alter table public.pos_recovery_incidents enable row level security;
drop policy if exists "administrators and owners view POS recovery incidents" on public.pos_recovery_incidents;
create policy "administrators and owners view POS recovery incidents" on public.pos_recovery_incidents
  for select using (public.can_monitor_stall(stall_id));
grant select on public.pos_recovery_incidents to anon, authenticated;

create or replace function public.active_pos_device(p_device_id uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select public.current_app_role() = 'cashier'
    and exists (
      select 1 from public.devices d
      where d.id = p_device_id
        and d.stall_id = public.current_app_stall_id()
        and d.is_active and d.deleted_at is null
        and d.hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '')
    );
$$;
revoke all on function public.active_pos_device(uuid) from public;
grant execute on function public.active_pos_device(uuid) to anon, authenticated;

create or replace function public.get_recovered_pos_day(p_device_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare day_row public.business_days%rowtype; incident_row public.pos_recovery_incidents%rowtype;
begin
  if not public.active_pos_device(p_device_id) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
  end if;
  select * into day_row from public.business_days
    where stall_id = public.current_app_stall_id() and device_id = p_device_id
      and closed_at is null and deleted_at is null order by opened_at desc limit 1;
  if day_row.id is null then return null; end if;
  select * into incident_row from public.pos_recovery_incidents
    where business_day_id = day_row.id and new_device_id = p_device_id
      and status in ('activated', 'reviewed') order by created_at desc limit 1;
  if incident_row.id is null then return null; end if;
  return jsonb_build_object('id', day_row.id, 'business_date', day_row.business_date,
    'opened_at', day_row.opened_at, 'cashier_id', day_row.cashier_id,
    'opening_notes', day_row.opening_notes,
    'known_sales', incident_row.known_sales,
    'known_orders', incident_row.known_orders,
    'known_deductions', incident_row.known_deductions,
    'known_profit_deductions', incident_row.known_profit_deductions,
    'known_cogs', incident_row.known_cogs,
    'known_waste', incident_row.known_waste);
end;
$$;
revoke all on function public.get_recovered_pos_day(uuid) from public;
grant execute on function public.get_recovered_pos_day(uuid) to anon, authenticated;

create or replace function public.get_pos_device_status(p_stall_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare active_row public.devices%rowtype; pending_row public.devices%rowtype;
  open_day public.business_days%rowtype; recovery_row public.pos_recovery_incidents%rowtype;
begin
  if not public.can_manage_stall(p_stall_id) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  select * into active_row from public.devices
    where stall_id = p_stall_id and is_active and deleted_at is null and hardware_id is not null
    order by updated_at desc limit 1;
  select * into pending_row from public.devices
    where stall_id = p_stall_id and is_active and deleted_at is null and hardware_id is null
      and activation_expires_at > now() order by updated_at desc limit 1;
  select * into open_day from public.business_days
    where stall_id = p_stall_id and closed_at is null and deleted_at is null
    order by opened_at desc limit 1;
  select * into recovery_row from public.pos_recovery_incidents
    where stall_id = p_stall_id and status <> 'reviewed' order by created_at desc limit 1;
  return jsonb_build_object(
    'active_device_id', active_row.id, 'active_device_name', active_row.device_name,
    'transfer_ready_at', active_row.transfer_ready_at,
    'pending_code_expires_at', pending_row.activation_expires_at,
    'open_business_date', open_day.business_date,
    'recovery_incident_id', recovery_row.id, 'recovery_status', recovery_row.status,
    'recovery_business_date', recovery_row.business_date,
    'recovery_known_sales', recovery_row.known_sales,
    'recovery_known_orders', recovery_row.known_orders,
    'recovery_known_deductions', recovery_row.known_deductions,
    'recovery_known_profit_deductions', recovery_row.known_profit_deductions,
    'recovery_known_cogs', recovery_row.known_cogs,
    'recovery_known_waste', recovery_row.known_waste
  );
end;
$$;
revoke all on function public.get_pos_device_status(uuid) from public;
grant execute on function public.get_pos_device_status(uuid) to anon, authenticated;

create or replace function public.prepare_pos_replacement(p_device_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
begin
  if not public.active_pos_device(p_device_id) then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501'; end if;
  if exists (select 1 from public.business_days
             where stall_id = public.current_app_stall_id() and closed_at is null and deleted_at is null) then
    raise exception 'Close and sync the operating day before replacing this POS' using errcode = '22023';
  end if;
  update public.devices set transfer_ready_at = now() where id = p_device_id;
  insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id)
  values (public.current_app_user_id(), public.current_app_stall_id(), 'device.transfer_ready', 'device', p_device_id::text);
  return jsonb_build_object('status', 'ready');
end;
$$;
revoke all on function public.prepare_pos_replacement(uuid) from public;
grant execute on function public.prepare_pos_replacement(uuid) to anon, authenticated;

-- Recovery records what the server actually knows. An open day remains open
-- and is transferred to the new POS after activation; no missing sale is invented.
create or replace function public.authorize_pos_recovery(p_stall_id uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare active_row public.devices%rowtype; open_day public.business_days%rowtype;
  incident_id uuid; server_sales numeric(12,2) := 0; server_deductions numeric(12,2) := 0;
  server_orders integer := 0;
  server_profit_deductions numeric(12,2) := 0;
  server_cogs numeric(12,2) := 0; server_waste numeric(12,2) := 0;
begin
  if not public.can_manage_stall(p_stall_id) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if length(trim(coalesce(p_reason, ''))) < 10 then
    raise exception 'Describe why the old POS cannot prepare for replacement' using errcode = '22023';
  end if;
  select * into active_row from public.devices
    where stall_id = p_stall_id and is_active and deleted_at is null and hardware_id is not null
    order by updated_at desc limit 1 for update;
  if active_row.id is null then raise exception 'No active POS needs recovery' using errcode = '22023'; end if;
  select * into open_day from public.business_days
    where stall_id = p_stall_id and closed_at is null and deleted_at is null
    order by opened_at desc limit 1 for update;
  if open_day.id is not null then
    if open_day.device_id <> active_row.id then
      raise exception 'The open day belongs to a different POS and needs investigation' using errcode = '22023';
    end if;
    select coalesce(sum(total_amount), 0) into server_sales from public.transactions
      where stall_id = p_stall_id and status = 'completed' and deleted_at is null
        and (occurred_at at time zone 'Asia/Manila')::date = open_day.business_date;
    select count(*)::integer into server_orders from public.transactions
      where stall_id = p_stall_id and status = 'completed' and deleted_at is null
        and (occurred_at at time zone 'Asia/Manila')::date = open_day.business_date;
    select coalesce(sum(amount), 0) into server_deductions from public.revenue_deductions
      where business_day_id = open_day.id;
    select coalesce(sum(amount), 0) into server_profit_deductions from public.revenue_deductions
      where business_day_id = open_day.id and affects_profit;
    select coalesce(sum(ti.quantity * p.cost_price), 0) into server_cogs
      from public.transaction_items ti join public.transactions t on t.id = ti.transaction_id
      join public.products p on p.id = ti.product_id
      where t.stall_id = p_stall_id and t.status = 'completed' and t.deleted_at is null
        and (t.occurred_at at time zone 'Asia/Manila')::date = open_day.business_date
        and ti.deleted_at is null;
    select coalesce(sum(abs(il.quantity_delta) * (p.cost_price / nullif(p.pack_size * p.conversion_rate, 0))), 0)
      into server_waste from public.inventory_ledger il join public.products p on p.id = il.product_id
      where il.stall_id = p_stall_id and (il.occurred_at at time zone 'Asia/Manila')::date = open_day.business_date
        and il.movement_type = 'adjustment' and il.quantity_delta < 0 and il.deleted_at is null;
  end if;
  select id into incident_id from public.pos_recovery_incidents
    where stall_id = p_stall_id and old_device_id = active_row.id and status = 'pending'
    order by created_at desc limit 1 for update;
  if incident_id is null then
    insert into public.pos_recovery_incidents(stall_id, old_device_id, business_day_id, business_date,
      reason, known_sales, known_orders, known_deductions, known_profit_deductions, known_cogs, known_waste)
    values (p_stall_id, active_row.id, open_day.id, open_day.business_date,
      trim(p_reason), server_sales, server_orders, server_deductions, server_profit_deductions,
      server_cogs, server_waste) returning id into incident_id;
  else
    update public.pos_recovery_incidents set reason = trim(p_reason), business_day_id = open_day.id,
      business_date = open_day.business_date, known_sales = server_sales, known_orders = server_orders,
      known_deductions = server_deductions, known_profit_deductions = server_profit_deductions,
      known_cogs = server_cogs,
      known_waste = server_waste where id = incident_id;
  end if;
  update public.devices set transfer_ready_at = now() where id = active_row.id;
  insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), p_stall_id, 'device.recovery_authorized', 'device',
    active_row.id::text, jsonb_build_object('reason', trim(p_reason), 'incident_id', incident_id,
      'business_date', open_day.business_date, 'known_sales', server_sales, 'known_orders', server_orders,
      'known_deductions', server_deductions, 'known_profit_deductions', server_profit_deductions,
      'known_cogs', server_cogs,
      'known_waste', server_waste));
  return jsonb_build_object('status', 'ready', 'device_id', active_row.id,
    'incident_id', incident_id, 'open_business_date', open_day.business_date,
    'known_sales', server_sales, 'known_orders', server_orders, 'known_deductions', server_deductions,
    'known_profit_deductions', server_profit_deductions,
    'known_cogs', server_cogs, 'known_waste', server_waste);
end;
$$;
revoke all on function public.authorize_pos_recovery(uuid,text) from public;
grant execute on function public.authorize_pos_recovery(uuid,text) to anon, authenticated;

create or replace function public.review_pos_recovery(p_incident_id uuid, p_notes text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare incident public.pos_recovery_incidents%rowtype;
begin
  select * into incident from public.pos_recovery_incidents where id = p_incident_id for update;
  if incident.id is null or not public.can_manage_stall(incident.stall_id) then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  if incident.status <> 'activated' then
    raise exception 'Activate the replacement POS before reviewing recovery' using errcode = '22023';
  end if;
  if incident.business_day_id is not null and exists (select 1 from public.business_days
      where id = incident.business_day_id and closed_at is null) then
    raise exception 'Close the recovered operating day before review' using errcode = '22023';
  end if;
  if length(trim(coalesce(p_notes, ''))) < 10 then
    raise exception 'Record the cash and receipt reconciliation outcome' using errcode = '22023';
  end if;
  update public.pos_recovery_incidents set status = 'reviewed',
    review_notes = trim(p_notes), reviewed_at = now() where id = incident.id;
  insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), incident.stall_id, 'device.recovery_reviewed',
    'pos_recovery_incident', incident.id::text, jsonb_build_object('notes', trim(p_notes)));
  return jsonb_build_object('status', 'reviewed', 'incident_id', incident.id);
end;
$$;
revoke all on function public.review_pos_recovery(uuid,text) from public;
grant execute on function public.review_pos_recovery(uuid,text) to anon, authenticated;

create or replace function public.revoke_pending_pos_activation(p_stall_id uuid)
returns integer language plpgsql security definer set search_path = public as $$
declare revoked_count integer;
begin
  if not public.can_manage_stall(p_stall_id) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  update public.devices set is_active = false where stall_id = p_stall_id
    and hardware_id is null and is_active and deleted_at is null;
  get diagnostics revoked_count = row_count;
  insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, details)
  values (public.current_app_user_id(), p_stall_id, 'device.activation_revoked', 'device',
    jsonb_build_object('count', revoked_count));
  return revoked_count;
end;
$$;
revoke all on function public.revoke_pending_pos_activation(uuid) from public;
grant execute on function public.revoke_pending_pos_activation(uuid) to anon, authenticated;

create or replace function public.create_device_activation(p_stall_id uuid, p_device_name text)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare activation_code text := upper(encode(gen_random_bytes(8), 'hex')); new_id uuid;
  code_expires_at timestamptz; active_row public.devices%rowtype;
begin
  if not public.can_manage_stall(p_stall_id) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  select * into active_row from public.devices
    where stall_id = p_stall_id and is_active and deleted_at is null and hardware_id is not null
    order by updated_at desc limit 1 for update;
  if active_row.id is not null then
    code_expires_at := now() + interval '30 minutes';
    if exists (select 1 from public.business_days where stall_id = p_stall_id and closed_at is null and deleted_at is null)
      and not exists (select 1 from public.pos_recovery_incidents
        where stall_id = p_stall_id and old_device_id = active_row.id and status = 'pending'
          and business_day_id in (select id from public.business_days
            where stall_id = p_stall_id and closed_at is null and deleted_at is null)) then
      raise exception 'Close and sync the operating day, or authorize lost-POS recovery first' using errcode = '22023';
    end if;
    if active_row.transfer_ready_at is null or active_row.transfer_ready_at < now() - interval '30 minutes' then
      raise exception 'Sync the old POS and prepare it for replacement in Device information first' using errcode = '22023';
    end if;
  end if;
  if code_expires_at is null then code_expires_at := now() + interval '24 hours'; end if;
  update public.devices set is_active = false
    where stall_id = p_stall_id and hardware_id is null and is_active and deleted_at is null;
  insert into public.devices (stall_id, device_name, activation_code_hash, activation_expires_at)
  values (p_stall_id, coalesce(nullif(trim(p_device_name), ''), 'Stall POS'),
    encode(digest(activation_code, 'sha256'), 'hex'), code_expires_at) returning id into new_id;
  insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id)
  values (public.current_app_user_id(), p_stall_id, 'device.activation_created', 'device', new_id::text);
  return jsonb_build_object('device_id', new_id, 'activation_code', activation_code,
    'expires_at', code_expires_at);
end;
$$;

create or replace function public.activate_pos_device(p_activation_code text, p_hardware_id text)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare matched public.devices%rowtype; old_device public.devices%rowtype;
  open_day public.business_days%rowtype; incident_row public.pos_recovery_incidents%rowtype;
  resume_day jsonb;
begin
  if public.current_app_role() <> 'cashier' then raise exception 'CASHIER_REQUIRED' using errcode = '42501'; end if;
  if nullif(trim(p_hardware_id), '') is null or trim(p_hardware_id) is distinct from
    coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '') then
    raise exception 'DEVICE_ID_MISMATCH' using errcode = '42501';
  end if;
  select * into matched from public.devices where stall_id = public.current_app_stall_id()
    and is_active and deleted_at is null and hardware_id is null
    and activation_expires_at > now()
    and activation_code_hash = encode(digest(upper(trim(p_activation_code)), 'sha256'), 'hex') for update;
  if not found then raise exception 'Invalid or expired activation code' using errcode = '28000'; end if;
  select * into old_device from public.devices where stall_id = matched.stall_id
    and id <> matched.id and is_active and deleted_at is null and hardware_id is not null
    order by updated_at desc limit 1 for update;
  if old_device.id is not null and
     (old_device.transfer_ready_at is null or old_device.transfer_ready_at < now() - interval '30 minutes') then
    raise exception 'The old POS must sync and prepare again before replacement' using errcode = '22023';
  end if;
  select * into open_day from public.business_days
    where stall_id = matched.stall_id and closed_at is null and deleted_at is null
    order by opened_at desc limit 1 for update;
  if open_day.id is not null then
    select * into incident_row from public.pos_recovery_incidents
      where stall_id = matched.stall_id and old_device_id = old_device.id
        and business_day_id = open_day.id and status = 'pending'
      order by created_at desc limit 1 for update;
    if incident_row.id is null then
      raise exception 'Close and sync the operating day, or authorize lost-POS recovery first' using errcode = '22023';
    end if;
  end if;
  if exists (select 1 from public.devices where hardware_id = trim(p_hardware_id)
    and stall_id <> matched.stall_id and is_active and deleted_at is null) then
    raise exception 'This phone is still registered to another stall' using errcode = '23505';
  end if;
  update public.devices set is_active = false where id <> matched.id
    and stall_id = matched.stall_id and is_active and deleted_at is null;
  update public.devices set hardware_id = trim(p_hardware_id), activation_code_hash = encode(digest(gen_random_bytes(32), 'sha256'), 'hex'),
    activation_expires_at = null where id = matched.id;
  if open_day.id is not null then
    update public.business_days set device_id = matched.id where id = open_day.id;
    resume_day := jsonb_build_object('id', open_day.id, 'business_date', open_day.business_date,
      'opened_at', open_day.opened_at, 'cashier_id', open_day.cashier_id,
      'opening_notes', open_day.opening_notes,
      'known_sales', incident_row.known_sales,
      'known_orders', incident_row.known_orders,
      'known_deductions', incident_row.known_deductions,
      'known_profit_deductions', incident_row.known_profit_deductions,
      'known_cogs', incident_row.known_cogs,
      'known_waste', incident_row.known_waste);
  end if;
  update public.pos_recovery_incidents set new_device_id = matched.id, status = 'activated'
    where stall_id = matched.stall_id and old_device_id = old_device.id and status = 'pending';
  insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id)
  values (public.current_app_user_id(), matched.stall_id, 'device.activated', 'device', matched.id::text);
  return jsonb_build_object('device_id', matched.id, 'stall_id', matched.stall_id,
    'resume_day', resume_day);
end;
$$;

-- Signing in again on the old phone cancels its preparation. The new phone's
-- pending code then cannot be redeemed until the old phone prepares again.
do $$ begin
  if to_regprocedure('public.login_pos_with_password_unchecked(text,text,text)') is null then
    if to_regprocedure('public.login_pos_with_password(text,text,text)') is null then
      raise exception 'Cannot install POS login guard: source function is missing';
    end if;
    alter function public.login_pos_with_password(text,text,text) rename to login_pos_with_password_unchecked;
  end if;
end $$;
revoke all on function public.login_pos_with_password_unchecked(text,text,text) from public, anon, authenticated;
create or replace function public.login_pos_with_password(p_stall_code text, p_email text, p_password text)
returns table(session_token text, user_id uuid, stall_id uuid, display_name text,
  role public.app_role, expires_at timestamptz, device_id uuid, is_activated boolean)
language plpgsql security definer set search_path = public as $$
declare signed_in record;
begin
  select * into signed_in from public.login_pos_with_password_unchecked(p_stall_code, p_email, p_password);
  if signed_in.device_id is not null then
    update public.devices set transfer_ready_at = null where id = signed_in.device_id;
  end if;
  return query select signed_in.session_token, signed_in.user_id, signed_in.stall_id,
    signed_in.display_name, signed_in.role, signed_in.expires_at,
    signed_in.device_id, signed_in.is_activated;
end;
$$;
revoke all on function public.login_pos_with_password(text,text,text) from public;
grant execute on function public.login_pos_with_password(text,text,text) to anon, authenticated;

-- Keep a single server operating-day identity. A replacement POS must never
-- accept a different local ID as a successful duplicate acknowledgement.
do $$ begin
  if to_regprocedure('public.push_business_day_unchecked(jsonb)') is null then
    if to_regprocedure('public.push_business_day(jsonb)') is null then raise exception 'Cannot install business-day guard: source function is missing'; end if;
    alter function public.push_business_day(jsonb) rename to push_business_day_unchecked;
  end if;
end $$;
revoke all on function public.push_business_day_unchecked(jsonb) from public, anon, authenticated;
create or replace function public.push_business_day(p_day jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare existing_id uuid;
begin
  if not public.active_pos_device((p_day ->> 'device_id')::uuid) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
  end if;
  select id into existing_id from public.business_days
    where stall_id = public.current_app_stall_id() and business_date = (p_day ->> 'business_date')::date and deleted_at is null;
  if existing_id is not null and existing_id <> (p_day ->> 'id')::uuid then
    raise exception 'OPERATING_DAY_TRANSFER_REQUIRED' using errcode = '23505';
  end if;
  update public.devices set transfer_ready_at = null where id = (p_day ->> 'device_id')::uuid;
  return public.push_business_day_unchecked(p_day);
end;
$$;
revoke all on function public.push_business_day(jsonb) from public;
grant execute on function public.push_business_day(jsonb) to anon, authenticated;

-- An expired cashier session or inactive POS must not upload new sales.
do $$ begin
  if to_regprocedure('public.push_pos_transaction_unchecked(jsonb)') is null then
    if to_regprocedure('public.push_pos_transaction(jsonb)') is null then raise exception 'Cannot install transaction guard: source function is missing'; end if;
    alter function public.push_pos_transaction(jsonb) rename to push_pos_transaction_unchecked;
  end if;
end $$;
revoke all on function public.push_pos_transaction_unchecked(jsonb) from public, anon, authenticated;
create or replace function public.push_pos_transaction(p_transaction jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare device_uuid uuid := nullif(p_transaction ->> 'device_id', '')::uuid;
begin
  if (p_transaction ->> 'stall_id')::uuid is distinct from public.current_app_stall_id()
    or not public.active_pos_device(device_uuid) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
  end if;
  update public.devices set transfer_ready_at = null where id = device_uuid;
  return public.push_pos_transaction_unchecked(p_transaction);
end;
$$;
revoke all on function public.push_pos_transaction(jsonb) from public;
grant execute on function public.push_pos_transaction(jsonb) to anon, authenticated;

do $$ begin
  if to_regprocedure('public.push_pos_inventory_entry_unchecked(jsonb)') is null then
    if to_regprocedure('public.push_pos_inventory_entry(jsonb)') is null then raise exception 'Cannot install inventory-entry guard: source function is missing'; end if;
    alter function public.push_pos_inventory_entry(jsonb) rename to push_pos_inventory_entry_unchecked;
  end if;
end $$;
revoke all on function public.push_pos_inventory_entry_unchecked(jsonb) from public, anon, authenticated;
create or replace function public.push_pos_inventory_entry(p_entry jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare device_uuid uuid;
begin
  select id into device_uuid from public.devices
    where stall_id = public.current_app_stall_id() and is_active and deleted_at is null
      and hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '')
    limit 1;
  if (p_entry ->> 'stall_id')::uuid is distinct from public.current_app_stall_id()
    or not public.active_pos_device(device_uuid) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
  end if;
  update public.devices set transfer_ready_at = null where id = device_uuid;
  return public.push_pos_inventory_entry_unchecked(p_entry);
end;
$$;
revoke all on function public.push_pos_inventory_entry(jsonb) from public;
grant execute on function public.push_pos_inventory_entry(jsonb) to anon, authenticated;

do $$ begin
  if to_regprocedure('public.push_revenue_deduction_unchecked(jsonb)') is null then
    if to_regprocedure('public.push_revenue_deduction(jsonb)') is null then raise exception 'Cannot install deduction guard: source function is missing'; end if;
    alter function public.push_revenue_deduction(jsonb) rename to push_revenue_deduction_unchecked;
  end if;
end $$;
revoke all on function public.push_revenue_deduction_unchecked(jsonb) from public, anon, authenticated;
create or replace function public.push_revenue_deduction(p_deduction jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare device_uuid uuid; result jsonb; existing_profit boolean;
begin
  select device_id into device_uuid from public.business_days where stall_id = public.current_app_stall_id()
    and business_date = (p_deduction ->> 'business_date')::date and deleted_at is null;
  if not public.active_pos_device(device_uuid) then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501'; end if;
  update public.devices set transfer_ready_at = null where id = device_uuid;
  result := public.push_revenue_deduction_unchecked(p_deduction);
  if result ->> 'status' = 'accepted' then
    update public.revenue_deductions set affects_profit = coalesce((p_deduction ->> 'affects_profit')::boolean, true)
      where id = (p_deduction ->> 'id')::uuid;
  else
    select affects_profit into existing_profit from public.revenue_deductions where id = (p_deduction ->> 'id')::uuid;
    if existing_profit is distinct from coalesce((p_deduction ->> 'affects_profit')::boolean, true) then
      raise exception 'DEDUCTION_IDEMPOTENCY_CONFLICT' using errcode = '23505';
    end if;
  end if;
  return result;
end;
$$;
revoke all on function public.push_revenue_deduction(jsonb) from public;
grant execute on function public.push_revenue_deduction(jsonb) to anon, authenticated;

do $$ begin
  if to_regprocedure('public.push_daily_store_closing_unchecked(jsonb)') is null then
    if to_regprocedure('public.push_daily_store_closing(jsonb)') is null then raise exception 'Cannot install closing guard: source function is missing'; end if;
    alter function public.push_daily_store_closing(jsonb) rename to push_daily_store_closing_unchecked;
  end if;
end $$;
revoke all on function public.push_daily_store_closing_unchecked(jsonb) from public, anon, authenticated;
create or replace function public.push_daily_store_closing(p_closing jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare device_uuid uuid := nullif(p_closing ->> 'device_id', '')::uuid;
  result jsonb; cash_only numeric(12,2); effective_closing jsonb := p_closing;
  day_uuid uuid; recorded_deductions numeric(12,2);
begin
  if not public.active_pos_device(device_uuid) then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501'; end if;
  update public.devices set transfer_ready_at = null where id = device_uuid;
  select id into day_uuid from public.business_days
    where stall_id = public.current_app_stall_id() and business_date = (p_closing ->> 'business_date')::date;
  if exists (select 1 from public.pos_recovery_incidents
      where business_day_id = day_uuid and status in ('activated', 'reviewed')) then
    select coalesce(sum(amount), 0) into recorded_deductions from public.revenue_deductions
      where business_day_id = day_uuid;
    effective_closing := jsonb_set(effective_closing, '{revenue_deduction}', to_jsonb(recorded_deductions));
    effective_closing := jsonb_set(effective_closing, '{deduction_reason}',
      to_jsonb(case when recorded_deductions > 0 then 'See recorded deductions' else '' end));
  end if;
  result := public.push_daily_store_closing_unchecked(effective_closing);
  select coalesce(sum(amount), 0) into cash_only from public.revenue_deductions
    where business_day_id = day_uuid
      and not affects_profit;
  update public.daily_store_closings set net_profit = net_profit + cash_only
    where id = (result ->> 'closing_id')::uuid;
  return result;
end;
$$;
revoke all on function public.push_daily_store_closing(jsonb) from public;
grant execute on function public.push_daily_store_closing(jsonb) to anon, authenticated;
