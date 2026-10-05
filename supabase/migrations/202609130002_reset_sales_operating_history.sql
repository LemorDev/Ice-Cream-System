-- Expand the sales reset into a complete sales-and-operating-history reset.
-- An open day must be closed first so a connected POS cannot continue writing
-- into the history while it is being cleared.

create or replace function public.reset_stall_transactions(p_stall_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  reset_time timestamptz := clock_timestamp();
  transaction_count integer := 0;
  operating_day_count integer := 0;
begin
  if not public.is_system_admin() then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  if not exists (select 1 from public.stalls where id = p_stall_id and deleted_at is null) then
    raise exception 'Stall not found' using errcode = 'P0002';
  end if;
  if exists (
    select 1 from public.business_days
    where stall_id = p_stall_id and deleted_at is null and closed_at is null
  ) then
    raise exception 'Close the current operating day before resetting sales and operating history'
      using errcode = '55000';
  end if;

  select count(*)::integer into transaction_count
  from public.transactions
  where stall_id = p_stall_id and deleted_at is null;

  select count(*)::integer into operating_day_count
  from public.business_days
  where stall_id = p_stall_id and deleted_at is null;

  update public.transaction_items item
  set deleted_at = reset_time
  from public.transactions tx
  where item.transaction_id = tx.id
    and tx.stall_id = p_stall_id
    and tx.deleted_at is null
    and item.deleted_at is null;

  update public.transactions
  set deleted_at = reset_time
  where stall_id = p_stall_id and deleted_at is null;

  update public.daily_closures
  set deleted_at = reset_time
  where stall_id = p_stall_id and deleted_at is null;

  delete from public.daily_store_closings where stall_id = p_stall_id;

  -- Daily summaries are removed first, so the completed operating days can be
  -- deleted and the stall may open a fresh day again—even on the same date.
  delete from public.business_days where stall_id = p_stall_id;

  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id, details)
  values (
    public.current_app_user_id(), p_stall_id, 'stall.operating_history_reset', 'stall', p_stall_id::text,
    jsonb_build_object(
      'transaction_count', transaction_count,
      'operating_day_count', operating_day_count,
      'reset_at', reset_time
    )
  );

  return jsonb_build_object(
    'scope', 'operating_history',
    'affected_records', transaction_count,
    'affected_operating_days', operating_day_count
  );
end;
$$;

revoke all on function public.reset_stall_transactions(uuid) from public;
grant execute on function public.reset_stall_transactions(uuid) to anon, authenticated;
