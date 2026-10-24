-- A closing is accepted only when the phone and server agree on the records
-- belonging to the opening operating day. Keep the older RPC available while
-- the compatible POS build is rolled out; the release gate retires it.
alter table public.inventory_ledger drop constraint if exists inventory_ledger_quantity_delta_check;
alter table public.inventory_ledger add constraint inventory_ledger_nonzero_or_waste_check
  check (quantity_delta<>0 or movement_type='void_waste');

create table public.sale_reversals (
  id uuid primary key,
  transaction_id uuid not null unique references public.transactions(id) on delete cascade,
  stall_id uuid not null references public.stalls(id),
  original_day_id uuid references public.business_days(id) on delete set null,
  payout_day_id uuid references public.business_days(id) on delete set null,
  cashier_id uuid not null references public.app_users(id),
  kind text not null check (kind in ('refund','void')),
  reason text not null check (nullif(trim(reason),'') is not null),
  restock boolean not null,
  cash_returned numeric(12,2) not null check (cash_returned>=0),
  occurred_at timestamptz not null,
  payload jsonb not null,
  created_at timestamptz not null default now()
);
create index sale_reversals_payout_day_idx on public.sale_reversals(payout_day_id,occurred_at);
alter table public.sale_reversals enable row level security;
create policy "authorized users view sale reversals" on public.sale_reversals for select
  using (public.can_access_stall(stall_id));
grant select on public.sale_reversals to anon,authenticated;

create function public.push_daily_store_closing_v2(p_closing jsonb)
returns jsonb language plpgsql security definer set search_path = public, private, extensions as $$
declare
  day_row public.business_days%rowtype;
  saved public.daily_store_closings%rowtype;
  stall_value uuid := public.current_app_stall_id();
  day_value uuid := nullif(p_closing->>'business_day_id','')::uuid;
  device_value uuid := nullif(p_closing->>'device_id','')::uuid;
  closing_value uuid := nullif(p_closing->>'id','')::uuid;
  sale_count integer;
  movement_count integer;
  deduction_count integer;
  reversal_count integer;
  gross_value numeric(12,2);
  cogs_value numeric(12,2);
  waste_value numeric(12,2);
  deduction_value numeric(12,2);
  profit_deduction numeric(12,2);
  overhead_value numeric(12,2);
  expected_value numeric(12,2);
  cash_sales_value numeric(12,2);
  payout_value numeric(12,2);
  collected_value numeric(12,2) := nullif(p_closing->>'collected_cash','')::numeric(12,2);
begin
  if public.current_app_role()<>'cashier' or stall_value is null
    or nullif(p_closing->>'stall_id','')::uuid is distinct from stall_value
    or day_value is null or device_value is null or closing_value is null
    or not public.active_pos_device(device_value) then
    raise exception 'ACTIVE_CASHIER_DEVICE_REQUIRED' using errcode='42501';
  end if;
  select * into day_row from public.business_days d where d.id=day_value
    and d.stall_id=stall_value and d.device_id=device_value and d.deleted_at is null for update;
  if not found or day_row.closed_at is null
    or day_row.business_date is distinct from nullif(p_closing->>'business_date','')::date
    or day_row.closed_at is distinct from nullif(p_closing->>'closed_at','')::timestamptz then
    raise exception 'OPERATING_DAY_NOT_CLOSED' using errcode='22023';
  end if;

  select count(*),coalesce(sum(case when status='completed' then total_amount else 0 end),0),
    coalesce(sum(case when status='completed' then cogs else 0 end),0)
    into sale_count,gross_value,cogs_value from public.transactions
    where business_day_id=day_value and stall_id=stall_value and deleted_at is null;
  select coalesce(sum(case when status<>'voided' then total_amount else 0 end),0)
    into cash_sales_value from public.transactions
    where business_day_id=day_value and stall_id=stall_value and deleted_at is null;
  select count(*) into movement_count from public.inventory_ledger
    where business_day_id=day_value and stall_id=stall_value and deleted_at is null;
  select count(*),coalesce(sum(amount),0),
    coalesce(sum(case when affects_profit then amount else 0 end),0)
    into deduction_count,deduction_value,profit_deduction
    from public.revenue_deductions where business_day_id=day_value;
  select coalesce(sum(abs(quantity_delta)*coalesce(unit_cost,0)),0) into waste_value
    from public.inventory_ledger where business_day_id=day_value and stall_id=stall_value
      and movement_type='adjustment' and quantity_delta<0 and deleted_at is null;
  waste_value := waste_value + coalesce((select sum(sc.cost_total)
    from public.sale_reversals r join public.sale_components sc on sc.transaction_id=r.transaction_id
    where r.original_day_id=day_value and not r.restock),0);
  select count(*),coalesce(sum(case when kind='refund' then cash_returned else 0 end),0)
    into reversal_count,payout_value from public.sale_reversals
    where payout_day_id=day_value and stall_id=stall_value;
  select coalesce(sum((item->>'dailyRate')::numeric),0) into overhead_value
    from public.stalls s,jsonb_array_elements(coalesce(s.overhead_config,'[]'::jsonb)) item
    where s.id=stall_value;
  expected_value := cash_sales_value-deduction_value-payout_value;
  if (p_closing->>'sale_count')::integer is distinct from sale_count
    or (p_closing->>'movement_count')::integer is distinct from movement_count
    or (p_closing->>'deduction_count')::integer is distinct from deduction_count
    or (p_closing->>'reversal_count')::integer is distinct from reversal_count
    or (p_closing->>'gross_sales')::numeric(12,2) is distinct from gross_value
    or (p_closing->>'cogs')::numeric(12,2) is distinct from cogs_value
    or (p_closing->>'waste_cost')::numeric(12,2) is distinct from round(waste_value,2)
    or (p_closing->>'revenue_deduction')::numeric(12,2) is distinct from deduction_value
    or (p_closing->>'expected_cash')::numeric(12,2) is distinct from expected_value then
    raise exception 'CLOSING_RECONCILIATION_MISMATCH' using errcode='22023';
  end if;
  if collected_value is null or collected_value<0 then
    raise exception 'INVALID_COLLECTED_CASH' using errcode='22023';
  end if;
  insert into public.daily_store_closings(id,stall_id,business_day_id,business_date,
    gross_sales,cogs,waste_cost,overhead_cost,revenue_deduction,deduction_reason,
    net_profit,expected_cash,collected_cash,device_id,closed_at)
  values(closing_value,stall_value,day_value,day_row.business_date,gross_value,cogs_value,
    round(waste_value,2),overhead_value,deduction_value,
    case when deduction_count>0 then 'See recorded deductions' else null end,
    gross_value-cogs_value-round(waste_value,2)-overhead_value-profit_deduction,
    expected_value,collected_value,device_value,day_row.closed_at)
  on conflict (stall_id,business_date) do update set
    gross_sales=excluded.gross_sales,cogs=excluded.cogs,waste_cost=excluded.waste_cost,
    overhead_cost=excluded.overhead_cost,revenue_deduction=excluded.revenue_deduction,
    deduction_reason=excluded.deduction_reason,net_profit=excluded.net_profit,
    expected_cash=excluded.expected_cash,collected_cash=excluded.collected_cash,
    updated_at=now()
  where public.daily_store_closings.id=excluded.id
    and public.daily_store_closings.business_day_id=excluded.business_day_id
    and public.daily_store_closings.device_id=excluded.device_id
  returning * into saved;
  if not found then raise exception 'CLOSING_IDENTITY_CONFLICT' using errcode='23505'; end if;
  return jsonb_build_object('status','accepted','closing_id',saved.id,
    'sale_count',sale_count,'movement_count',movement_count,'deduction_count',deduction_count);
end;
$$;
revoke all on function public.push_daily_store_closing_v2(jsonb) from public;
grant execute on function public.push_daily_store_closing_v2(jsonb) to anon,authenticated;

-- Serialize a late sale with closing. An exact replay remains available after
-- closing, while a new late sale stays on the phone for supervised recovery.
alter function public.push_pos_transaction_v2(jsonb) rename to push_pos_transaction_v2_unchecked;
revoke all on function public.push_pos_transaction_v2_unchecked(jsonb) from public,anon,authenticated;
create function public.push_pos_transaction_v2(p_transaction jsonb)
returns jsonb language plpgsql security definer set search_path = public, private, extensions as $$
declare day_value uuid := nullif(p_transaction->>'business_day_id','')::uuid;
  stall_value uuid := public.current_app_stall_id();
begin
  if public.current_app_role()<>'cashier' or day_value is null then
    raise exception 'CASHIER_REQUIRED' using errcode='42501';
  end if;
  perform 1 from public.business_days where id=day_value and stall_id=stall_value
    and deleted_at is null for update;
  if not found then raise exception 'OPERATING_DAY_MISMATCH' using errcode='22023'; end if;
  if exists(select 1 from public.daily_store_closings where business_day_id=day_value)
    and not exists(select 1 from public.transactions where id=nullif(p_transaction->>'id','')::uuid
      and stall_id=stall_value) then
    raise exception 'DAY_ALREADY_CLOSED_RECONCILE_SALE' using errcode='23503';
  end if;
  return public.push_pos_transaction_v2_unchecked(p_transaction);
end;
$$;
revoke all on function public.push_pos_transaction_v2(jsonb) from public;
grant execute on function public.push_pos_transaction_v2(jsonb) to anon,authenticated;
