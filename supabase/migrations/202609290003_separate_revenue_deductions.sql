-- Record cashier deductions when they happen, and resolve a closing against
-- the server's operating-day ID (which may differ from a retried local ID).
create table public.revenue_deductions (
  id uuid primary key,
  stall_id uuid not null references public.stalls(id),
  business_day_id uuid not null references public.business_days(id),
  amount numeric(12,2) not null check (amount > 0),
  reason text not null check (nullif(trim(reason), '') is not null),
  cashier_id uuid not null references public.app_users(id),
  occurred_at timestamptz not null,
  created_at timestamptz not null default now()
);
create index revenue_deductions_day_idx on public.revenue_deductions (business_day_id, occurred_at);
alter table public.revenue_deductions enable row level security;
create policy "authorized users view revenue deductions" on public.revenue_deductions for select
  using (public.can_access_stall(stall_id));
grant select on public.revenue_deductions to anon, authenticated;

create or replace function public.push_revenue_deduction(p_deduction jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  requested_id uuid := (p_deduction ->> 'id')::uuid;
  requested_amount numeric(12,2) := (p_deduction ->> 'amount')::numeric;
  requested_reason text := nullif(trim(p_deduction ->> 'reason'), '');
  day_row public.business_days%rowtype;
  saved_id uuid;
  gross_amount numeric(12,2);
  existing_deductions numeric(12,2);
begin
  if public.current_app_role() <> 'cashier' or (p_deduction ->> 'stall_id')::uuid <> public.current_app_stall_id()
    or (p_deduction ->> 'cashier_id')::uuid <> public.current_app_user_id() then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  if requested_amount <= 0 or requested_reason is null then
    raise exception 'A positive deduction and reason are required' using errcode = '22023';
  end if;
  select * into day_row from public.business_days
    where stall_id = public.current_app_stall_id()
      and business_date = (p_deduction ->> 'business_date')::date for update;
  if not found then raise exception 'Operating day not synced' using errcode = '23503'; end if;
  if exists (select 1 from public.revenue_deductions where id = requested_id and stall_id = day_row.stall_id) then
    return jsonb_build_object('status', 'duplicate', 'deduction_id', requested_id);
  end if;
  if (p_deduction ->> 'occurred_at')::timestamptz < day_row.opened_at
    or (day_row.closed_at is not null and (p_deduction ->> 'occurred_at')::timestamptz > day_row.closed_at) then
    raise exception 'Deduction is outside the operating day' using errcode = '22023';
  end if;
  select coalesce(sum(total_amount), 0) into gross_amount from public.transactions
    where stall_id = day_row.stall_id and status = 'completed'
      and (occurred_at at time zone 'Asia/Manila')::date = day_row.business_date and deleted_at is null;
  select coalesce(sum(amount), 0) into existing_deductions from public.revenue_deductions
    where business_day_id = day_row.id;
  if existing_deductions + requested_amount > gross_amount then
    raise exception 'Deduction exceeds remaining sales revenue' using errcode = '22023';
  end if;
  insert into public.revenue_deductions
    (id, stall_id, business_day_id, amount, reason, cashier_id, occurred_at)
  values (requested_id, day_row.stall_id, day_row.id, requested_amount, requested_reason,
    public.current_app_user_id(), (p_deduction ->> 'occurred_at')::timestamptz)
  on conflict (id) do nothing returning id into saved_id;
  return jsonb_build_object('status', case when saved_id is null then 'duplicate' else 'accepted' end,
    'deduction_id', requested_id);
end;
$$;
grant execute on function public.push_revenue_deduction(jsonb) to anon, authenticated;

create or replace function public.push_daily_store_closing(p_closing jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  saved public.daily_store_closings%rowtype;
  day_row public.business_days%rowtype;
  current_stall uuid := public.current_app_stall_id();
  close_date date := (p_closing ->> 'business_date')::date;
  authoritative_gross numeric(12,2);
  authoritative_cogs numeric(12,2);
  authoritative_waste numeric(12,2);
  authoritative_overhead numeric(12,2);
  recorded_deduction numeric(12,2);
  requested_deduction numeric(12,2) := coalesce(nullif(p_closing ->> 'revenue_deduction', '')::numeric, 0);
  requested_reason text := nullif(trim(p_closing ->> 'deduction_reason'), '');
  total_deduction numeric(12,2);
begin
  if public.current_app_role() <> 'cashier' or (p_closing ->> 'stall_id')::uuid <> current_stall then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  select * into day_row from public.business_days
    where stall_id = current_stall and business_date = close_date;
  if not found then raise exception 'Operating day not synced' using errcode = '23503'; end if;

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
  select coalesce(sum(amount), 0) into recorded_deduction from public.revenue_deductions
    where business_day_id = day_row.id;
  if requested_reason = 'See recorded deductions' and recorded_deduction <> requested_deduction then
    raise exception 'Revenue deductions have not all synced' using errcode = '23503';
  end if;
  -- Older queued closings may still carry a single embedded deduction.
  total_deduction := case when recorded_deduction > 0 then recorded_deduction else requested_deduction end;
  if total_deduction < 0 or total_deduction > authoritative_gross then
    raise exception 'INVALID_REVENUE_DEDUCTION' using errcode = '22023';
  end if;
  if total_deduction > 0 and recorded_deduction = 0 and requested_reason is null then
    raise exception 'DEDUCTION_REASON_REQUIRED' using errcode = '22023';
  end if;

  insert into public.daily_store_closings (
    id, stall_id, business_day_id, business_date, gross_sales, cogs, waste_cost, overhead_cost,
    revenue_deduction, deduction_reason, net_profit, expected_cash, collected_cash, device_id, closed_at
  ) values (
    (p_closing ->> 'id')::uuid, current_stall, day_row.id, close_date,
    authoritative_gross, authoritative_cogs, authoritative_waste, authoritative_overhead,
    total_deduction,
    case when recorded_deduction > 0 then 'See recorded deductions' else requested_reason end,
    authoritative_gross - authoritative_cogs - authoritative_waste - authoritative_overhead - total_deduction,
    authoritative_gross - total_deduction, (p_closing ->> 'collected_cash')::numeric,
    day_row.device_id, coalesce(nullif(p_closing ->> 'closed_at', '')::timestamptz, now())
  ) on conflict (stall_id, business_date) do update set
    business_day_id = excluded.business_day_id, device_id = excluded.device_id,
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
