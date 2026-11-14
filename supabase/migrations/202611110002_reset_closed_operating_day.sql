-- Keep the retired day ID and its ledger links while freeing the business date.
alter table public.business_days drop constraint business_days_stall_id_business_date_key;
create unique index business_days_active_date_key
  on public.business_days(stall_id, business_date) where deleted_at is null;

create or replace function public.push_business_day(p_day jsonb)
returns jsonb language plpgsql security definer set search_path = pg_catalog, public as $$
declare
  day_id uuid := (p_day->>'id')::uuid;
  stall uuid := public.current_app_stall_id();
  device uuid := (p_day->>'device_id')::uuid;
  saved uuid;
begin
  if not coalesce(public.active_pos_device(device), false) or (p_day->>'stall_id')::uuid is distinct from stall then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
  end if;

  insert into public.business_days as day (id, stall_id, device_id, cashier_id, business_date,
    opened_at, opening_notes, closed_at, closing_cash_total, closing_notes)
  values (day_id, stall, device, public.current_app_user_id(), (p_day->>'business_date')::date,
    (p_day->>'opened_at')::timestamptz, nullif(trim(p_day->>'opening_notes'), ''),
    nullif(p_day->>'closed_at', '')::timestamptz, nullif(p_day->>'closing_cash_total', '')::numeric(12,2),
    nullif(trim(p_day->>'closing_notes'), ''))
  on conflict (id) do update set
    closed_at = coalesce(day.closed_at, excluded.closed_at),
    closing_cash_total = coalesce(day.closing_cash_total, excluded.closing_cash_total),
    closing_notes = coalesce(day.closing_notes, excluded.closing_notes)
  where day.deleted_at is null and day.stall_id = excluded.stall_id
    and day.device_id = excluded.device_id and day.business_date = excluded.business_date
    and day.opened_at = excluded.opened_at
  returning id into saved;
  if saved is null then
    raise exception 'OPERATING_DAY_RESET_OR_IDENTITY_MISMATCH' using errcode = '22023';
  end if;
  update public.devices set transfer_ready_at = null where id = device;
  return jsonb_build_object('status', 'accepted', 'business_day_id', saved);
exception when unique_violation then
  raise exception 'OPERATING_DAY_TRANSFER_REQUIRED' using errcode = '23505';
end;
$$;
drop function public.push_business_day_unchecked(jsonb);

create function public.reset_closed_operating_day(p_stall_id uuid, p_business_day_id uuid)
returns jsonb language plpgsql security definer set search_path = pg_catalog, public as $$
declare
  day_row public.business_days%rowtype;
  archived_closing public.daily_store_closings%rowtype;
begin
  if not coalesce(public.can_manage_stall(p_stall_id), false) then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  select * into day_row from public.business_days
  where id = p_business_day_id and stall_id = p_stall_id
    and business_date = (statement_timestamp() at time zone 'Asia/Manila')::date for update;
  if not found then
    raise exception 'Select today''s operating day for this stall.' using errcode = 'P0002';
  end if;
  if day_row.deleted_at is not null then
    return jsonb_build_object('scope', 'operating_day', 'affected_records', 0);
  end if;
  if day_row.closed_at is null then
    raise exception 'Close the operating day on the POS and sync it first.' using errcode = '22023';
  end if;

  delete from public.daily_store_closings
  where business_day_id = day_row.id and stall_id = p_stall_id and closed_at = day_row.closed_at
  returning * into archived_closing;
  if not found then
    raise exception 'Sync the final closing from the POS before resetting the operating day.' using errcode = '22023';
  end if;
  update public.business_days set deleted_at = clock_timestamp() where id = day_row.id;
  insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), p_stall_id, 'business_day.reset', 'business_day', day_row.id::text,
    jsonb_build_object('business_date', day_row.business_date, 'closing', to_jsonb(archived_closing)));
  return jsonb_build_object('scope', 'operating_day', 'affected_records', 1);
end;
$$;

create function public.get_pos_operating_day_reset(p_business_day_id uuid, p_pending_closing_id uuid default null)
returns boolean language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
  if public.current_app_role() is distinct from 'cashier'::public.app_role then
    raise exception 'CASHIER_REQUIRED' using errcode = '42501';
  end if;
  return exists (select 1 from public.business_days d
    where d.id = p_business_day_id and d.stall_id = public.current_app_stall_id() and d.deleted_at is not null
      and (p_pending_closing_id is null or exists (
        select 1 from public.security_audit_log audit
        where audit.stall_id = d.stall_id and audit.action = 'business_day.reset'
          and audit.target_id = d.id::text and audit.details->'closing'->>'id' = p_pending_closing_id::text
      )));
end;
$$;

revoke all on function public.reset_closed_operating_day(uuid,uuid), public.get_pos_operating_day_reset(uuid,uuid)
from public, anon, authenticated;
grant execute on function public.reset_closed_operating_day(uuid,uuid), public.get_pos_operating_day_reset(uuid,uuid)
to anon, authenticated;
