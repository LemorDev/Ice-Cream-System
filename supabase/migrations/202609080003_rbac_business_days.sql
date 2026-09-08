-- Three-role RBAC, multi-stall owner assignments, verified POS activation,
-- staff administration, and cashier operating-day records.

alter table public.devices
  add column if not exists hardware_id text;
create unique index if not exists devices_hardware_id_active_idx
  on public.devices (hardware_id) where hardware_id is not null and is_active and deleted_at is null;

create table public.owner_stall_access (
  user_id uuid not null references public.app_users(id) on delete cascade,
  stall_id uuid not null references public.stalls(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, stall_id)
);

insert into public.owner_stall_access (user_id, stall_id)
select id, stall_id from public.app_users where role = 'owner'
on conflict do nothing;

create table public.business_days (
  id uuid primary key,
  stall_id uuid not null references public.stalls(id),
  device_id uuid not null references public.devices(id),
  cashier_id uuid not null references public.app_users(id),
  business_date date not null,
  opened_at timestamptz not null,
  opening_notes text,
  closed_at timestamptz,
  closing_cash_total numeric(12,2) check (closing_cash_total is null or closing_cash_total >= 0),
  closing_notes text,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (stall_id, business_date),
  check (closed_at is null or closed_at >= opened_at)
);

create table public.security_audit_log (
  id bigint generated always as identity primary key,
  actor_user_id uuid references public.app_users(id),
  stall_id uuid references public.stalls(id),
  action text not null,
  target_type text not null,
  target_id text,
  details jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null default now()
);

create trigger business_days_set_updated_at before update on public.business_days
for each row execute function public.set_updated_at();

create or replace function public.is_system_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select public.current_app_role() = 'system_admin';
$$;

create or replace function public.is_owner()
returns boolean language sql stable security definer set search_path = public as $$
  select public.current_app_role() = 'owner';
$$;

-- Compatibility for older policies/functions while every client migrates.
create or replace function public.is_manager()
returns boolean language sql stable security definer set search_path = public as $$
  select public.current_app_role() in ('system_admin', 'owner');
$$;

create or replace function public.can_access_stall(p_stall_id uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select case public.current_app_role()
    when 'system_admin' then true
    when 'owner' then exists (
      select 1 from public.owner_stall_access osa
      where osa.user_id = public.current_app_user_id() and osa.stall_id = p_stall_id
    )
    when 'cashier' then p_stall_id = public.current_app_stall_id()
    else false
  end;
$$;

create or replace function public.can_manage_stall(p_stall_id uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select public.current_app_role() = 'system_admin'
    or (public.current_app_role() = 'owner' and public.can_access_stall(p_stall_id));
$$;

alter table public.owner_stall_access enable row level security;
alter table public.business_days enable row level security;
alter table public.security_audit_log enable row level security;

drop policy if exists "stall members can view stall" on public.stalls;
drop policy if exists "managers update their stall" on public.stalls;
drop policy if exists "managers can view users" on public.app_users;
drop policy if exists "stall members can view devices" on public.devices;
drop policy if exists "managers manage devices" on public.devices;
drop policy if exists "stall members view categories" on public.product_categories;
drop policy if exists "managers manage categories" on public.product_categories;
drop policy if exists "stall members view products" on public.products;
drop policy if exists "managers manage products" on public.products;
drop policy if exists "stall members view inventory" on public.inventory_ledger;
drop policy if exists "managers add inventory" on public.inventory_ledger;
drop policy if exists "stall members view transactions" on public.transactions;
drop policy if exists "stall members create transactions" on public.transactions;
drop policy if exists "managers update transactions" on public.transactions;
drop policy if exists "stall members view transaction items" on public.transaction_items;
drop policy if exists "stall members create transaction items" on public.transaction_items;
drop policy if exists "stall members view daily closures" on public.daily_closures;
drop policy if exists "managers manage daily closures" on public.daily_closures;

create policy "administrators view accessible stalls" on public.stalls for select
  using (public.can_manage_stall(id));
create policy "administrators update accessible stalls" on public.stalls for update
  using (public.can_manage_stall(id)) with check (public.can_manage_stall(id));
create policy "system administrators create stalls" on public.stalls for insert
  with check (public.is_system_admin());

create policy "administrators view accessible devices" on public.devices for select
  using (public.can_manage_stall(stall_id));
create policy "administrators manage accessible devices" on public.devices for all
  using (public.can_manage_stall(stall_id)) with check (public.can_manage_stall(stall_id));

create policy "administrators view categories" on public.product_categories for select
  using (public.can_manage_stall(stall_id));
create policy "administrators manage categories" on public.product_categories for all
  using (public.can_manage_stall(stall_id)) with check (public.can_manage_stall(stall_id));
create policy "administrators view products" on public.products for select
  using (public.can_manage_stall(stall_id));
create policy "administrators manage products" on public.products for all
  using (public.can_manage_stall(stall_id)) with check (public.can_manage_stall(stall_id));

create policy "authorized users view inventory" on public.inventory_ledger for select
  using (public.can_access_stall(stall_id));
create policy "administrators add inventory" on public.inventory_ledger for insert
  with check (public.can_manage_stall(stall_id));
create policy "authorized users view transactions" on public.transactions for select
  using (
    public.can_manage_stall(stall_id)
    or (public.current_app_role() = 'cashier' and stall_id = public.current_app_stall_id()
      and cashier_id = public.current_app_user_id())
  );
create policy "authorized users view transaction items" on public.transaction_items for select
  using (exists (
    select 1 from public.transactions t
    where t.id = transaction_id and (
      public.can_manage_stall(t.stall_id)
      or (public.current_app_role() = 'cashier' and t.stall_id = public.current_app_stall_id()
        and t.cashier_id = public.current_app_user_id())
    )
  ));
create policy "administrators update transactions" on public.transactions for update
  using (public.can_manage_stall(stall_id)) with check (public.can_manage_stall(stall_id));

create policy "administrators view legacy closures" on public.daily_closures for select
  using (public.can_manage_stall(stall_id));
create policy "authorized users view business days" on public.business_days for select
  using (
    public.can_manage_stall(stall_id)
    or (public.current_app_role() = 'cashier' and stall_id = public.current_app_stall_id()
      and cashier_id = public.current_app_user_id())
  );
create policy "administrators view owner assignments" on public.owner_stall_access for select
  using (public.is_system_admin() or user_id = public.current_app_user_id());
create policy "administrators view security audit" on public.security_audit_log for select
  using (public.is_system_admin() or public.can_manage_stall(stall_id));

revoke insert, update, delete on public.transactions, public.transaction_items, public.daily_closures from anon, authenticated;
revoke all on public.owner_stall_access, public.business_days, public.security_audit_log from anon, authenticated;
grant select on public.business_days to anon, authenticated;

create or replace function public.get_my_stalls()
returns table (id uuid, name text, code text, updated_at timestamptz, overhead_config jsonb)
language sql stable security definer set search_path = public as $$
  select s.id, s.name, s.code, s.updated_at, s.overhead_config
  from public.stalls s
  where s.deleted_at is null and public.can_manage_stall(s.id)
  order by s.name;
$$;

create or replace function public.list_managed_users(p_stall_id uuid)
returns table (
  id uuid, stall_id uuid, email text, display_name text, role public.app_role,
  is_active boolean, updated_at timestamptz, stall_ids uuid[]
)
language sql stable security definer set search_path = public as $$
  select u.id, u.stall_id, u.email, u.display_name, u.role, u.is_active, u.updated_at,
    case when u.role = 'owner' then coalesce(array_agg(distinct osa.stall_id) filter (where osa.stall_id is not null), '{}'::uuid[])
      else array[u.stall_id] end
  from public.app_users u
  left join public.owner_stall_access osa on osa.user_id = u.id
  where u.deleted_at is null
    and public.can_manage_stall(p_stall_id)
    and (
      (public.is_system_admin() and (
        u.stall_id = p_stall_id
        or exists (select 1 from public.owner_stall_access assigned where assigned.user_id = u.id and assigned.stall_id = p_stall_id)
      ) and u.role <> 'system_admin')
      or (public.is_owner() and u.stall_id = p_stall_id and u.role = 'cashier')
    )
  group by u.id
  order by u.display_name;
$$;

create or replace function public.save_managed_user(
  p_stall_id uuid,
  p_email text,
  p_display_name text,
  p_role public.app_role,
  p_password text default null,
  p_user_id uuid default null,
  p_is_active boolean default true
)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare
  target public.app_users%rowtype;
  saved public.app_users%rowtype;
begin
  if not public.can_manage_stall(p_stall_id) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if p_role = 'system_admin' or (public.is_owner() and p_role <> 'cashier') then
    raise exception 'ROLE_NOT_ALLOWED' using errcode = '42501';
  end if;
  if nullif(trim(p_email), '') is null or nullif(trim(p_display_name), '') is null then
    raise exception 'Email and display name are required' using errcode = '22023';
  end if;
  if p_user_id is null and (p_password is null or length(p_password) < 8) then
    raise exception 'A password of at least 8 characters is required' using errcode = '22023';
  end if;
  if p_password is not null and length(p_password) < 8 then
    raise exception 'Password must contain at least 8 characters' using errcode = '22023';
  end if;

  if p_user_id is null then
    insert into public.app_users (stall_id, email, display_name, role, password_hash, is_active)
    values (p_stall_id, lower(trim(p_email)), trim(p_display_name), p_role,
      crypt(p_password, gen_salt('bf')), p_is_active) returning * into saved;
  else
    select * into target from public.app_users where id = p_user_id and deleted_at is null for update;
    if not found or target.role = 'system_admin'
      or (public.is_owner() and (target.role <> 'cashier' or target.stall_id <> p_stall_id)) then
      raise exception 'USER_NOT_MANAGEABLE' using errcode = '42501';
    end if;
    update public.app_users set
      stall_id = p_stall_id,
      email = lower(trim(p_email)),
      display_name = trim(p_display_name),
      role = p_role,
      password_hash = case when p_password is null or p_password = '' then password_hash else crypt(p_password, gen_salt('bf')) end,
      is_active = p_is_active
    where id = p_user_id returning * into saved;
  end if;

  if saved.role = 'owner' then
    insert into public.owner_stall_access (user_id, stall_id) values (saved.id, p_stall_id) on conflict do nothing;
  else
    delete from public.owner_stall_access where user_id = saved.id;
  end if;
  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), p_stall_id, case when p_user_id is null then 'user.created' else 'user.updated' end,
    'app_user', saved.id::text, jsonb_build_object('role', saved.role, 'active', saved.is_active));
  return jsonb_build_object('id', saved.id, 'stall_id', saved.stall_id, 'email', saved.email,
    'display_name', saved.display_name, 'role', saved.role, 'is_active', saved.is_active, 'updated_at', saved.updated_at);
end;
$$;

create or replace function public.set_owner_stalls(p_owner_id uuid, p_stall_ids uuid[])
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_system_admin() then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if coalesce(array_length(p_stall_ids, 1), 0) = 0 then raise exception 'Assign at least one stall' using errcode = '22023'; end if;
  if not exists (select 1 from public.app_users where id = p_owner_id and role = 'owner' and deleted_at is null) then
    raise exception 'Owner not found' using errcode = 'P0002';
  end if;
  if exists (select 1 from unnest(p_stall_ids) requested(id) left join public.stalls s on s.id = requested.id where s.id is null or s.deleted_at is not null) then
    raise exception 'Invalid stall assignment' using errcode = '22023';
  end if;
  delete from public.owner_stall_access where user_id = p_owner_id;
  insert into public.owner_stall_access (user_id, stall_id) select p_owner_id, id from (select distinct unnest(p_stall_ids) id) assignments;
  update public.app_users set stall_id = p_stall_ids[1] where id = p_owner_id;
  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), p_stall_ids[1], 'owner.stalls_changed', 'app_user', p_owner_id::text,
    jsonb_build_object('stall_ids', p_stall_ids));
end;
$$;

create or replace function public.create_managed_stall(p_name text, p_code text)
returns uuid language plpgsql security definer set search_path = public as $$
declare new_id uuid;
begin
  if not public.is_system_admin() then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if length(trim(p_name)) < 2 or length(trim(p_code)) < 2 then raise exception 'Name and code are required' using errcode = '22023'; end if;
  insert into public.stalls (name, code) values (trim(p_name), upper(trim(p_code))) returning id into new_id;
  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id)
  values (public.current_app_user_id(), new_id, 'stall.created', 'stall', new_id::text);
  return new_id;
end;
$$;

create or replace function public.create_device_activation(p_stall_id uuid, p_device_name text)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare activation_code text := upper(substr(encode(gen_random_bytes(8), 'hex'), 1, 10)); new_id uuid;
begin
  if not public.can_manage_stall(p_stall_id) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  -- Invalidate older unused codes, but keep the working POS online until its
  -- replacement successfully redeems this code.
  update public.devices set is_active = false
  where stall_id = p_stall_id and hardware_id is null and is_active and deleted_at is null;
  insert into public.devices (stall_id, device_name, activation_code_hash)
  values (p_stall_id, coalesce(nullif(trim(p_device_name), ''), 'Stall POS'), encode(digest(activation_code, 'sha256'), 'hex'))
  returning id into new_id;
  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id)
  values (public.current_app_user_id(), p_stall_id, 'device.activation_created', 'device', new_id::text);
  return jsonb_build_object('device_id', new_id, 'activation_code', activation_code);
end;
$$;

create or replace function public.activate_pos_device(p_activation_code text, p_hardware_id text)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare matched public.devices%rowtype;
begin
  if public.current_app_role() <> 'cashier' then raise exception 'CASHIER_REQUIRED' using errcode = '42501'; end if;
  if nullif(trim(p_hardware_id), '') is null then raise exception 'DEVICE_ID_REQUIRED' using errcode = '22023'; end if;
  select * into matched from public.devices
  where stall_id = public.current_app_stall_id() and is_active and deleted_at is null
    and activation_code_hash = encode(digest(upper(trim(p_activation_code)), 'sha256'), 'hex') for update;
  if not found then raise exception 'Invalid or expired activation code' using errcode = '28000'; end if;
  if matched.hardware_id is not null and matched.hardware_id <> trim(p_hardware_id) then
    raise exception 'Activation code already used by another device' using errcode = '28000';
  end if;
  update public.devices set is_active = false
  where id <> matched.id and stall_id = matched.stall_id and is_active and deleted_at is null;
  update public.devices set hardware_id = trim(p_hardware_id), activation_code_hash = encode(digest(gen_random_bytes(32), 'sha256'), 'hex')
  where id = matched.id;
  return jsonb_build_object('device_id', matched.id, 'stall_id', matched.stall_id);
end;
$$;

create or replace function public.push_business_day(p_day jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare day_id uuid := (p_day ->> 'id')::uuid; current_stall uuid := public.current_app_stall_id(); device_uuid uuid := (p_day ->> 'device_id')::uuid; saved public.business_days%rowtype;
begin
  if public.current_app_role() <> 'cashier' then raise exception 'CASHIER_REQUIRED' using errcode = '42501'; end if;
  if (p_day ->> 'stall_id')::uuid <> current_stall then raise exception 'STALL_MISMATCH' using errcode = '42501'; end if;
  if not exists (select 1 from public.devices d where d.id = device_uuid and d.stall_id = current_stall and d.is_active and d.deleted_at is null
    and d.hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '')) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
  end if;
  insert into public.business_days (id, stall_id, device_id, cashier_id, business_date, opened_at, opening_notes, closed_at, closing_cash_total, closing_notes)
  values (day_id, current_stall, device_uuid, public.current_app_user_id(), (p_day ->> 'business_date')::date,
    (p_day ->> 'opened_at')::timestamptz, nullif(trim(p_day ->> 'opening_notes'), ''),
    nullif(p_day ->> 'closed_at', '')::timestamptz, nullif(p_day ->> 'closing_cash_total', '')::numeric(12,2),
    nullif(trim(p_day ->> 'closing_notes'), ''))
  on conflict (stall_id, business_date) do update set
    closed_at = coalesce(public.business_days.closed_at, excluded.closed_at),
    closing_cash_total = coalesce(public.business_days.closing_cash_total, excluded.closing_cash_total),
    closing_notes = coalesce(public.business_days.closing_notes, excluded.closing_notes)
  returning * into saved;
  return jsonb_build_object('status', case when saved.id = day_id then 'accepted' else 'duplicate' end, 'business_day_id', saved.id);
end;
$$;

-- Run this manually as the database owner on a fresh installation.
create or replace function public.create_initial_system_admin(
  p_stall_name text,
  p_stall_code text,
  p_email text,
  p_password text,
  p_display_name text
)
returns uuid language plpgsql security definer set search_path = public, extensions as $$
declare new_stall_id uuid;
begin
  if exists (select 1 from public.app_users) then raise exception 'Initial account already created'; end if;
  if length(p_password) < 8 then raise exception 'Password must contain at least 8 characters' using errcode = '22023'; end if;
  insert into public.stalls (name, code) values (trim(p_stall_name), upper(trim(p_stall_code))) returning id into new_stall_id;
  insert into public.app_users (stall_id, email, display_name, role, password_hash)
  values (new_stall_id, lower(trim(p_email)), trim(p_display_name), 'system_admin', crypt(p_password, gen_salt('bf')));
  return new_stall_id;
end;
$$;

-- Existing installations can promote one chosen Owner after the migration.
create or replace function public.promote_user_to_system_admin(p_email text)
returns uuid language plpgsql security definer set search_path = public as $$
declare promoted_id uuid;
begin
  update public.app_users set role = 'system_admin' where email = lower(trim(p_email)) and deleted_at is null returning id into promoted_id;
  if promoted_id is null then raise exception 'User not found'; end if;
  return promoted_id;
end;
$$;

drop function if exists public.create_initial_manager(text, text, text, text, text);
revoke all on function public.create_initial_system_admin(text,text,text,text,text) from public, anon, authenticated;
revoke all on function public.promote_user_to_system_admin(text) from public, anon, authenticated;
grant execute on function public.is_system_admin(), public.is_owner(), public.can_access_stall(uuid), public.can_manage_stall(uuid) to anon, authenticated;
grant execute on function public.get_my_stalls(), public.list_managed_users(uuid), public.save_managed_user(uuid,text,text,public.app_role,text,uuid,boolean), public.set_owner_stalls(uuid,uuid[]), public.create_managed_stall(text,text), public.create_device_activation(uuid,text), public.activate_pos_device(text,text), public.push_business_day(jsonb) to anon, authenticated;
