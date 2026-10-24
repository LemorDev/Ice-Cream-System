-- Return the existing open day to the same authenticated, active POS after an
-- app reinstall. Recovery incidents retain their frozen snapshot behavior.
create or replace function public.get_resumable_pos_day(p_device_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  day_row public.business_days%rowtype;
  recovered jsonb;
  known_sales numeric(12,2);
  known_orders integer;
  known_deductions numeric(12,2);
  known_profit_deductions numeric(12,2);
  known_cogs numeric(12,2);
  known_waste numeric(12,2);
begin
  if not public.active_pos_device(p_device_id) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
  end if;

  select * into day_row from public.business_days
    where stall_id = public.current_app_stall_id() and device_id = p_device_id
      and closed_at is null and deleted_at is null
    order by opened_at desc limit 1;
  if day_row.id is null then return null; end if;

  recovered := public.get_recovered_pos_day(p_device_id);
  if recovered is not null then return recovered; end if;

  select coalesce(sum(t.total_amount),0), count(*)::integer,
         coalesce(sum(t.cogs),0)
    into known_sales,known_orders,known_cogs
    from public.transactions t
    where t.stall_id=day_row.stall_id and t.business_day_id=day_row.id
      and t.status='completed' and t.deleted_at is null;
  select coalesce(sum(d.amount),0),
         coalesce(sum(d.amount) filter (where d.affects_profit),0)
    into known_deductions,known_profit_deductions
    from public.revenue_deductions d where d.business_day_id=day_row.id;
  select coalesce(sum(abs(l.quantity_delta)*coalesce(l.unit_cost,0)),0)
    into known_waste from public.inventory_ledger l
    where l.stall_id=day_row.stall_id and l.business_day_id=day_row.id
      and l.movement_type='adjustment' and l.quantity_delta<0 and l.deleted_at is null;
  known_waste := known_waste + coalesce((select sum(sc.cost_total)
    from public.sale_reversals r
    join public.sale_components sc on sc.transaction_id=r.transaction_id
    where r.original_day_id=day_row.id and not r.restock),0);

  return jsonb_build_object('id',day_row.id,'business_date',day_row.business_date,
    'opened_at',day_row.opened_at,'cashier_id',day_row.cashier_id,
    'opening_notes',day_row.opening_notes,'known_sales',known_sales,
    'known_orders',known_orders,'known_deductions',known_deductions,
    'known_profit_deductions',known_profit_deductions,'known_cogs',known_cogs,
    'known_waste',known_waste);
end;
$$;
revoke all on function public.get_resumable_pos_day(uuid) from public;
grant execute on function public.get_resumable_pos_day(uuid) to anon,authenticated;
