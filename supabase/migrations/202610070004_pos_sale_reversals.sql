-- Full receipt reversal. The original day gets corrected sale/profit figures;
-- a refund's physical cash payout belongs to the day on which it was paid.
create function public.push_pos_sale_reversal(p_reversal jsonb)
returns jsonb language plpgsql security definer set search_path = public, private, extensions as $$
declare
  reversal_value uuid := nullif(p_reversal->>'id','')::uuid;
  transaction_value uuid := nullif(p_reversal->>'transaction_id','')::uuid;
  payout_day_value uuid := nullif(p_reversal->>'payout_day_id','')::uuid;
  stall_value uuid := public.current_app_stall_id();
  kind_value text := p_reversal->>'kind';
  reason_value text := nullif(trim(p_reversal->>'reason'),'');
  restock_value boolean := (p_reversal->>'restock')::boolean;
  cash_value numeric(12,2) := nullif(p_reversal->>'cash_returned','')::numeric(12,2);
  occurred_value timestamptz := nullif(p_reversal->>'occurred_at','')::timestamptz;
  sale public.transactions%rowtype;
  payout_day public.business_days%rowtype;
  prior public.sale_reversals%rowtype;
  adjusted_gross numeric(12,2);
  adjusted_cogs numeric(12,2);
  adjusted_waste numeric(12,2);
  profit_deduction numeric(12,2);
  movement_count integer;
  supplied_movement_count integer;
begin
  if public.current_app_role()<>'cashier' or stall_value is null or reversal_value is null
    or transaction_value is null or payout_day_value is null or occurred_value is null
    or nullif(p_reversal->>'stall_id','')::uuid is distinct from stall_value then
    raise exception 'INVALID_REVERSAL_IDENTITY' using errcode='42501';
  end if;
  if kind_value not in ('refund','void') or reason_value is null or length(reason_value)>500
    or restock_value is null or cash_value is null then
    raise exception 'INVALID_REVERSAL' using errcode='22023';
  end if;
  select * into sale from public.transactions where id=transaction_value and stall_id=stall_value
    and deleted_at is null;
  if not found or sale.business_day_id is null then
    raise exception 'SALE_NOT_FOUND_OR_LEGACY' using errcode='P0002';
  end if;
  -- Both sides of a cross-day refund are locked before the sale row, matching
  -- the day-first order used by sale upload and closing.
  perform 1 from public.business_days d where d.id in (sale.business_day_id,payout_day_value)
    and d.stall_id=stall_value and d.deleted_at is null order by d.id for update;
  select * into payout_day from public.business_days d where d.id=payout_day_value
    and d.stall_id=stall_value and d.deleted_at is null;
  if not found or not public.active_pos_device(payout_day.device_id)
    or occurred_value<payout_day.opened_at
    or occurred_value>coalesce(payout_day.closed_at,'infinity'::timestamptz) then
    raise exception 'PAYOUT_DAY_MISMATCH' using errcode='22023';
  end if;
  select * into sale from public.transactions where id=transaction_value and stall_id=stall_value
    and deleted_at is null for update;
  select * into prior from public.sale_reversals
    where id=reversal_value or transaction_id=transaction_value limit 1;
  if found then
    if prior.id is distinct from reversal_value or prior.transaction_id is distinct from transaction_value
      or prior.payload is distinct from p_reversal then
      raise exception 'REVERSAL_IDEMPOTENCY_CONFLICT' using errcode='23505';
    end if;
    return jsonb_build_object('status','duplicate','reversal_id',reversal_value,
      'transaction_id',transaction_value);
  end if;
  if sale.status<>'completed' or not exists(select 1 from public.sale_components
      where transaction_id=transaction_value) then
    raise exception 'SALE_NOT_REVERSIBLE' using errcode='22023';
  end if;
  if jsonb_typeof(p_reversal->'movements') is distinct from 'array' then
    raise exception 'REVERSAL_MOVEMENTS_REQUIRED' using errcode='22023';
  end if;
  select count(*),count(distinct movement->>'product_id') into supplied_movement_count,movement_count
    from jsonb_array_elements(p_reversal->'movements') movement;
  if supplied_movement_count=0 or supplied_movement_count<>movement_count
    or supplied_movement_count<>(select count(*) from public.sale_components where transaction_id=transaction_value)
    or exists(select 1 from jsonb_array_elements(p_reversal->'movements') movement
      left join public.sale_components sc on sc.transaction_id=transaction_value
        and sc.product_id=(movement->>'product_id')::uuid
      where nullif(movement->>'id','') is null or sc.product_id is null) then
    raise exception 'REVERSAL_MOVEMENT_MISMATCH' using errcode='22023';
  end if;
  if (kind_value='refund' and cash_value<>sale.total_amount)
    or (kind_value='void' and cash_value<>0) then
    raise exception 'FULL_REFUND_CASH_MISMATCH' using errcode='22023';
  end if;
  if exists(select 1 from public.daily_store_closings where business_day_id=payout_day_value) then
    raise exception 'PAYOUT_DAY_ALREADY_CLOSED' using errcode='23503';
  end if;
  insert into public.sale_reversals(id,transaction_id,stall_id,original_day_id,payout_day_id,
    cashier_id,kind,reason,restock,cash_returned,occurred_at,payload)
  values(reversal_value,transaction_value,stall_value,sale.business_day_id,payout_day_value,
    public.current_app_user_id(),kind_value,reason_value,restock_value,cash_value,occurred_value,p_reversal);
  update public.transactions set status=
    case when kind_value='refund' then 'refunded' else 'voided' end::public.transaction_status
    where id=transaction_value;
  insert into public.inventory_ledger(id,stall_id,business_day_id,product_id,quantity_delta,
    movement_type,reason,reference_id,occurred_at,unit_cost)
  select (movement->>'id')::uuid,stall_value,sale.business_day_id,sc.product_id,
    case when restock_value then sc.quantity else 0 end,
    case when restock_value then 'void_restock' else 'void_waste' end::public.inventory_movement_type,
    reason_value,transaction_value,occurred_value,
    round(sc.cost_total/sc.quantity,8)
  from public.sale_components sc
  join jsonb_array_elements(p_reversal->'movements') movement
    on (movement->>'product_id')::uuid=sc.product_id
  where sc.transaction_id=transaction_value;
  get diagnostics movement_count=row_count;

  -- Reopen only the financial figures of an already closed original day. Its
  -- cash count remains the record of what was physically in the till at close.
  if exists(select 1 from public.daily_store_closings where business_day_id=sale.business_day_id) then
    select coalesce(sum(total_amount),0),coalesce(sum(cogs),0)
      into adjusted_gross,adjusted_cogs from public.transactions
      where business_day_id=sale.business_day_id and status='completed' and deleted_at is null;
    select coalesce(sum(abs(quantity_delta)*coalesce(unit_cost,0)),0)
      into adjusted_waste from public.inventory_ledger
      where business_day_id=sale.business_day_id and movement_type='adjustment'
        and quantity_delta<0 and deleted_at is null;
    adjusted_waste := adjusted_waste + coalesce((select sum(sc.cost_total)
      from public.sale_reversals r join public.sale_components sc on sc.transaction_id=r.transaction_id
      where r.original_day_id=sale.business_day_id and not r.restock),0);
    select coalesce(sum(amount),0) into profit_deduction from public.revenue_deductions
      where business_day_id=sale.business_day_id and affects_profit;
    update public.daily_store_closings set gross_sales=adjusted_gross,cogs=adjusted_cogs,
      waste_cost=round(adjusted_waste,2),
      net_profit=adjusted_gross-adjusted_cogs-round(adjusted_waste,2)-overhead_cost-profit_deduction
      where business_day_id=sale.business_day_id;
  end if;
  return jsonb_build_object('status','accepted','reversal_id',reversal_value,
    'transaction_id',transaction_value,'inserted_ledger',movement_count);
exception when unique_violation then
  select * into prior from public.sale_reversals where id=reversal_value or transaction_id=transaction_value limit 1;
  if prior.id=reversal_value and prior.transaction_id=transaction_value
    and prior.payload=p_reversal then
    return jsonb_build_object('status','duplicate','reversal_id',reversal_value,
      'transaction_id',transaction_value,'inserted_ledger',0);
  end if;
  raise exception 'REVERSAL_IDEMPOTENCY_CONFLICT' using errcode='23505';
end;
$$;
revoke all on function public.push_pos_sale_reversal(jsonb) from public;
grant execute on function public.push_pos_sale_reversal(jsonb) to anon,authenticated;

-- The older IMS action can change stock without a cash payout or day audit.
revoke all on function public.reverse_sale_inventory_ledger(uuid,text,boolean)
  from public,anon,authenticated;
