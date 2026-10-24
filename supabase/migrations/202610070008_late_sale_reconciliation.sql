-- A sale that was saved before close can arrive after its closing was posted.
-- Post it once, correct the original day, and expose the late upload to Owner.
create table public.closed_day_corrections (
  transaction_id uuid primary key references public.transactions(id) on delete cascade,
  stall_id uuid not null references public.stalls(id),
  business_day_id uuid references public.business_days(id) on delete set null,
  added_gross numeric(12,2) not null,
  added_cogs numeric(12,2) not null,
  reason text not null default 'Sale uploaded after closing',
  posted_at timestamptz not null default now()
);
alter table public.closed_day_corrections enable row level security;
create policy "owners view closed day corrections" on public.closed_day_corrections for select
  using (public.current_app_role() in ('owner','system_admin')
    and public.can_access_stall(stall_id));
grant select on public.closed_day_corrections to anon,authenticated;

create or replace function public.push_pos_transaction_v2(p_transaction jsonb)
returns jsonb language plpgsql security definer set search_path = public, private, extensions as $$
declare
  day_value uuid := nullif(p_transaction->>'business_day_id','')::uuid;
  stall_value uuid := public.current_app_stall_id();
  was_closed boolean;
  result jsonb;
  gross_value numeric(12,2);
  cogs_value numeric(12,2);
  waste_value numeric(12,2);
  profit_deduction numeric(12,2);
  cash_sales numeric(12,2);
  deduction_value numeric(12,2);
  payout_value numeric(12,2);
begin
  if public.current_app_role()<>'cashier' or day_value is null then
    raise exception 'CASHIER_REQUIRED' using errcode='42501';
  end if;
  perform 1 from public.business_days where id=day_value and stall_id=stall_value
    and deleted_at is null for update;
  if not found then raise exception 'OPERATING_DAY_MISMATCH' using errcode='22023'; end if;
  was_closed := exists(select 1 from public.daily_store_closings where business_day_id=day_value);
  result := public.push_pos_transaction_v2_unchecked(p_transaction);
  if was_closed and result->>'status'='accepted' then
    insert into public.closed_day_corrections(transaction_id,stall_id,business_day_id,added_gross,added_cogs)
    values((p_transaction->>'id')::uuid,stall_value,day_value,
      (p_transaction->>'total_amount')::numeric(12,2),
      (select cogs from public.transactions where id=(p_transaction->>'id')::uuid));
    select coalesce(sum(case when status='completed' then total_amount else 0 end),0),
      coalesce(sum(case when status='completed' then cogs else 0 end),0),
      coalesce(sum(case when status<>'voided' then total_amount else 0 end),0)
      into gross_value,cogs_value,cash_sales from public.transactions
      where business_day_id=day_value and stall_id=stall_value and deleted_at is null;
    select coalesce(sum(abs(quantity_delta)*coalesce(unit_cost,0)),0)
      into waste_value from public.inventory_ledger where business_day_id=day_value
        and movement_type='adjustment' and quantity_delta<0 and deleted_at is null;
    waste_value := waste_value + coalesce((select sum(sc.cost_total)
      from public.sale_reversals r join public.sale_components sc on sc.transaction_id=r.transaction_id
      where r.original_day_id=day_value and not r.restock),0);
    select coalesce(sum(amount),0),coalesce(sum(case when affects_profit then amount else 0 end),0)
      into deduction_value,profit_deduction from public.revenue_deductions
      where business_day_id=day_value;
    select coalesce(sum(cash_returned),0) into payout_value from public.sale_reversals
      where payout_day_id=day_value and kind='refund';
    update public.daily_store_closings set gross_sales=gross_value,cogs=cogs_value,
      waste_cost=round(waste_value,2),
      net_profit=gross_value-cogs_value-round(waste_value,2)-overhead_cost-profit_deduction,
      expected_cash=cash_sales-deduction_value-payout_value
      where business_day_id=day_value;
  end if;
  return result;
end;
$$;
revoke all on function public.push_pos_transaction_v2(jsonb) from public;
grant execute on function public.push_pos_transaction_v2(jsonb) to anon,authenticated;
