-- Cashier-entered, reasoned revenue deductions on daily closings.

alter table public.daily_store_closings
  add column revenue_deduction numeric(12,2) not null default 0 check (revenue_deduction >= 0),
  add column deduction_reason text,
  add constraint daily_store_closings_deduction_reason_required check (
    revenue_deduction = 0 or nullif(trim(deduction_reason), '') is not null
  );

create or replace function public.push_daily_store_closing(p_closing jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  saved public.daily_store_closings%rowtype;
  current_stall uuid := public.current_app_stall_id();
  close_date date := (p_closing ->> 'business_date')::date;
  authoritative_gross numeric(12,2);
  authoritative_cogs numeric(12,2);
  authoritative_waste numeric(12,2);
  authoritative_overhead numeric(12,2);
  requested_deduction numeric(12,2) := coalesce(nullif(p_closing ->> 'revenue_deduction', '')::numeric, 0);
  requested_reason text := nullif(trim(p_closing ->> 'deduction_reason'), '');
begin
  if public.current_app_role() <> 'cashier' or (p_closing ->> 'stall_id')::uuid <> current_stall then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;

  select coalesce(sum(t.total_amount), 0) into authoritative_gross from public.transactions t
    where t.stall_id = current_stall and t.status = 'completed'
      and (t.occurred_at at time zone 'Asia/Manila')::date = close_date and t.deleted_at is null;
  select coalesce(sum(ti.quantity * p.cost_price), 0) into authoritative_cogs
    from public.transaction_items ti join public.transactions t on t.id = ti.transaction_id
    join public.products p on p.id = ti.product_id
    where t.stall_id = current_stall and t.status = 'completed'
      and (t.occurred_at at time zone 'Asia/Manila')::date = close_date and ti.deleted_at is null;
  select coalesce(sum(abs(il.quantity_delta) * (p.cost_price / nullif(p.pack_size * p.conversion_rate, 0))), 0)
    into authoritative_waste from public.inventory_ledger il join public.products p on p.id = il.product_id
    where il.stall_id = current_stall and (il.occurred_at at time zone 'Asia/Manila')::date = close_date
      and il.movement_type = 'adjustment' and il.quantity_delta < 0 and il.deleted_at is null;
  select coalesce(sum((item ->> 'dailyRate')::numeric), 0) into authoritative_overhead
    from public.stalls s, jsonb_array_elements(coalesce(s.overhead_config, '[]'::jsonb)) item where s.id = current_stall;

  if requested_deduction < 0 or requested_deduction > authoritative_gross then
    raise exception 'INVALID_REVENUE_DEDUCTION' using errcode = '22023';
  end if;
  if requested_deduction > 0 and requested_reason is null then
    raise exception 'DEDUCTION_REASON_REQUIRED' using errcode = '22023';
  end if;

  insert into public.daily_store_closings (
    id, stall_id, business_day_id, business_date, gross_sales, cogs, waste_cost, overhead_cost,
    revenue_deduction, deduction_reason, net_profit, expected_cash, collected_cash, device_id, closed_at
  ) values (
    (p_closing ->> 'id')::uuid, current_stall, nullif(p_closing ->> 'business_day_id', '')::uuid,
    close_date, authoritative_gross, authoritative_cogs, authoritative_waste, authoritative_overhead,
    requested_deduction, requested_reason,
    authoritative_gross - authoritative_cogs - authoritative_waste - authoritative_overhead - requested_deduction,
    authoritative_gross - requested_deduction, (p_closing ->> 'collected_cash')::numeric,
    nullif(p_closing ->> 'device_id', '')::uuid,
    coalesce(nullif(p_closing ->> 'closed_at', '')::timestamptz, now())
  )
  on conflict (stall_id, business_date) do update set
    collected_cash = excluded.collected_cash, expected_cash = excluded.expected_cash,
    gross_sales = excluded.gross_sales, cogs = excluded.cogs, waste_cost = excluded.waste_cost,
    overhead_cost = excluded.overhead_cost, revenue_deduction = excluded.revenue_deduction,
    deduction_reason = excluded.deduction_reason, net_profit = excluded.net_profit,
    closed_at = excluded.closed_at
  returning * into saved;

  return jsonb_build_object('status', 'accepted', 'closing_id', saved.id);
end;
$$;

grant execute on function public.push_daily_store_closing(jsonb) to anon, authenticated;
