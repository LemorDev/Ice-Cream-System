-- A resumed POS may have a frozen server-only prefix of sales and movements.
-- Return authoritative day row counts so its final closing can still use the
-- strict reconciliation RPC after every local queue item has been acknowledged.
create or replace function public.get_pos_day_closing_counts(
  p_business_day_id uuid,
  p_device_id uuid
)
returns jsonb language plpgsql stable security definer
set search_path = public as $$
declare
  day_row public.business_days%rowtype;
begin
  if public.current_app_role() <> 'cashier'
    or not public.active_pos_device(p_device_id) then
    raise exception 'ACTIVE_CASHIER_DEVICE_REQUIRED' using errcode = '42501';
  end if;

  select * into day_row from public.business_days d
    where d.id = p_business_day_id
      and d.stall_id = public.current_app_stall_id()
      and d.device_id = p_device_id
      and d.deleted_at is null
      and d.closed_at is not null;
  if not found then
    raise exception 'OPERATING_DAY_NOT_CLOSED' using errcode = '22023';
  end if;

  return jsonb_build_object(
    'sale_count', (select count(*) from public.transactions t
      where t.business_day_id = day_row.id and t.stall_id = day_row.stall_id
        and t.deleted_at is null),
    'movement_count', (select count(*) from public.inventory_ledger l
      where l.business_day_id = day_row.id and l.stall_id = day_row.stall_id
        and l.deleted_at is null),
    'deduction_count', (select count(*) from public.revenue_deductions d
      where d.business_day_id = day_row.id),
    'reversal_count', (select count(*) from public.sale_reversals r
      where r.payout_day_id = day_row.id and r.stall_id = day_row.stall_id)
  );
end;
$$;

revoke all on function public.get_pos_day_closing_counts(uuid, uuid) from public;
grant execute on function public.get_pos_day_closing_counts(uuid, uuid) to anon, authenticated;
