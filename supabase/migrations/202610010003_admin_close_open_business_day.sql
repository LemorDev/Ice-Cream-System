-- Allow IMS managers to close an open POS day after the phone is unavailable.
-- This is an audited fallback: it records only server-synced sales and deductions.
create or replace function public.admin_close_open_business_day(
  p_business_day_id uuid,
  p_collected_cash numeric,
  p_reason text
)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  day_row public.business_days%rowtype;
  gross numeric(12,2);
  cogs numeric(12,2);
  waste numeric(12,2);
  overhead numeric(12,2);
  all_deductions numeric(12,2);
  profit_deductions numeric(12,2);
  close_time timestamptz := now();
  close_reason text := nullif(trim(p_reason), '');
  closing_id uuid := gen_random_uuid();
begin
  if not public.can_manage_stall((select stall_id from public.business_days where id = p_business_day_id)) then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  if p_collected_cash is null or p_collected_cash < 0 then
    raise exception 'Enter the physical cash count (zero or more).' using errcode = '22023';
  end if;
  if length(coalesce(close_reason, '')) < 10 then
    raise exception 'Explain why IMS is closing this day; enter at least 10 characters.' using errcode = '22023';
  end if;

  select * into day_row from public.business_days where id = p_business_day_id for update;
  if not found or day_row.deleted_at is not null then
    raise exception 'OPERATING_DAY_NOT_FOUND' using errcode = 'P0002';
  end if;
  if day_row.closed_at is not null then
    raise exception 'OPERATING_DAY_ALREADY_CLOSED' using errcode = '23505';
  end if;
  if exists (select 1 from public.daily_store_closings where stall_id = day_row.stall_id and business_date = day_row.business_date) then
    raise exception 'A daily closing already exists for this operating day.' using errcode = '23505';
  end if;

  select coalesce(sum(t.total_amount), 0) into gross
  from public.transactions t where t.stall_id = day_row.stall_id and t.status = 'completed'
    and (t.occurred_at at time zone 'Asia/Manila')::date = day_row.business_date and t.deleted_at is null;
  select coalesce(sum(ti.quantity * p.cost_price), 0) into cogs
  from public.transaction_items ti join public.transactions t on t.id = ti.transaction_id
    join public.products p on p.id = ti.product_id
  where t.stall_id = day_row.stall_id and t.status = 'completed'
    and (t.occurred_at at time zone 'Asia/Manila')::date = day_row.business_date and ti.deleted_at is null;
  select coalesce(sum(abs(il.quantity_delta) * (p.cost_price / nullif(p.pack_size * p.conversion_rate, 0))), 0) into waste
  from public.inventory_ledger il join public.products p on p.id = il.product_id
  where il.stall_id = day_row.stall_id and (il.occurred_at at time zone 'Asia/Manila')::date = day_row.business_date
    and il.movement_type = 'adjustment' and il.quantity_delta < 0 and il.deleted_at is null;
  select coalesce(sum((item ->> 'dailyRate')::numeric), 0) into overhead
  from public.stalls s, jsonb_array_elements(coalesce(s.overhead_config, '[]'::jsonb)) item
  where s.id = day_row.stall_id;
  select coalesce(sum(amount), 0), coalesce(sum(amount) filter (where affects_profit), 0)
    into all_deductions, profit_deductions
  from public.revenue_deductions where business_day_id = day_row.id;

  update public.business_days set closed_at = close_time, closing_cash_total = p_collected_cash,
    closing_notes = 'Closed from IMS: ' || close_reason where id = day_row.id;
  update public.devices set is_active = false
    where id = day_row.device_id and is_active and deleted_at is null;

  insert into public.daily_store_closings (
    id, stall_id, business_day_id, business_date, gross_sales, cogs, waste_cost, overhead_cost,
    revenue_deduction, deduction_reason, net_profit, expected_cash, collected_cash, device_id, closed_at
  ) values (
    closing_id, day_row.stall_id, day_row.id, day_row.business_date, gross, cogs, waste, overhead,
    all_deductions, case when all_deductions > 0 then 'See recorded deductions' end,
    gross - cogs - waste - overhead - profit_deductions, gross - all_deductions,
    p_collected_cash, day_row.device_id, close_time
  );

  insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), day_row.stall_id, 'business_day.admin_closed', 'business_day', day_row.id::text,
    jsonb_build_object('business_date', day_row.business_date, 'collected_cash', p_collected_cash,
      'server_known_sales', gross, 'server_known_deductions', all_deductions,
      'deactivated_device_id', day_row.device_id, 'reason', close_reason));

  return jsonb_build_object('status', 'closed', 'business_day_id', day_row.id,
    'closing_id', closing_id, 'server_known_sales', gross,
    'server_known_deductions', all_deductions, 'collected_cash', p_collected_cash);
end;
$$;
revoke all on function public.admin_close_open_business_day(uuid, numeric, text) from public;
grant execute on function public.admin_close_open_business_day(uuid, numeric, text) to authenticated;
