-- Book tracked powder once, on opening. Older queued openings without a cost
-- snapshot retain their original zero-gram movement and replay identity.


create or replace function public.guard_powder_pack_ledger() returns trigger
language plpgsql security definer set search_path=public as $$
declare tracked boolean; sealed integer; total numeric;
begin
  select tracks_powder_packs into tracked from public.products where id=new.product_id and stall_id=new.stall_id for update;
  if not found then raise exception 'INVALID_PRODUCT' using errcode='22023'; end if;
  if new.movement_type::text='receive' and tracked then
    if new.quantity_delta<=0 or new.quantity_delta<>trunc(new.quantity_delta/1000)*1000 then
      raise exception 'POWDER_RECEIVE_WHOLE_PACKS_REQUIRED' using errcode='22023';
    end if;
    new.sealed_packs_delta := (new.quantity_delta/1000)::integer;
  elsif new.movement_type::text in ('pack_unsealed','pack_reclassified') then
    if not tracked or new.sealed_packs_delta=0
      or (new.movement_type='pack_reclassified' and new.quantity_delta<>0)
      or (new.movement_type='pack_unsealed' and new.quantity_delta<>0
        and new.quantity_delta<>new.sealed_packs_delta*1000::numeric) then
      raise exception 'INVALID_POWDER_PACK_MOVEMENT' using errcode='22023';
    end if;
    if new.movement_type::text='pack_unsealed' and new.sealed_packs_delta>=0 then
      raise exception 'INVALID_PACK_OPENING' using errcode='22023';
    end if;
    select coalesce(sum(sealed_packs_delta),0),coalesce(sum(quantity_delta),0)
      into sealed,total from public.inventory_ledger
      where stall_id=new.stall_id and product_id=new.product_id and deleted_at is null;
    if sealed+new.sealed_packs_delta<0 then
      raise exception 'INSUFFICIENT_SEALED_PACKS' using errcode='P0001';
    end if;
    if new.movement_type='pack_unsealed' and new.quantity_delta<0 and total+new.quantity_delta<0 then
      raise exception 'INSUFFICIENT_STOCK' using errcode='P0001';
    end if;
    if new.movement_type::text='pack_reclassified' and total-(sealed+new.sealed_packs_delta)*1000<0 then
      raise exception 'INSUFFICIENT_OPERATIONAL_POWDER' using errcode='P0001';
    end if;
  elsif new.sealed_packs_delta<>0 then
    raise exception 'INVALID_SEALED_PACK_DELTA' using errcode='22023';
  end if;
  return new;
end;
$$;

create function private.powder_opening_cost(p_day_id uuid) returns numeric
language sql stable set search_path=public as $$
  select coalesce(sum(round(-l.quantity_delta*l.unit_cost,2)),0)
  from public.inventory_ledger l
  where l.business_day_id=p_day_id and l.movement_type='pack_unsealed'
    and l.quantity_delta<0 and l.deleted_at is null;
$$;
revoke all on function private.powder_opening_cost(uuid) from public,anon,authenticated;

create function public.push_pos_powder_opening(p_entry jsonb) returns jsonb
language plpgsql security definer set search_path=public,private,extensions as $$
declare stall_value uuid:=public.current_app_stall_id(); day_value uuid:=nullif(p_entry->>'business_day_id','')::uuid;
  entry_value uuid:=nullif(p_entry->>'id','')::uuid; product_value uuid:=nullif(p_entry->>'product_id','')::uuid;
  count_value integer:=nullif(p_entry->>'packs_opened','')::integer;
  cost_value numeric(12,2):=(p_entry->>'cost_total')::numeric(12,2);
  quantity_value numeric:=-count_value*1000::numeric;
  unit_cost_value numeric(18,8):=round(cost_value/nullif(-quantity_value,0),8);
  existing public.inventory_ledger%rowtype;
begin
  if public.current_app_role() is distinct from 'cashier' or stall_value is null
    or nullif(p_entry->>'stall_id','')::uuid is distinct from stall_value then
    raise exception 'ACTIVE_CASHIER_DEVICE_REQUIRED' using errcode='42501';
  end if;
  if day_value is null or entry_value is null or product_value is null
    or count_value is null or count_value<=0 or count_value>1000
    or cost_value is null or cost_value<0 or cost_value='NaN'::numeric then
    raise exception 'INVALID_PACK_OPENING' using errcode='22023';
  end if;
  perform 1 from public.business_days d where d.id=day_value and d.stall_id=stall_value
    and d.deleted_at is null and public.active_pos_device(d.device_id)
    and (p_entry->>'occurred_at')::timestamptz>=d.opened_at
    and (p_entry->>'occurred_at')::timestamptz<=coalesce(d.closed_at,'infinity'::timestamptz) for update;
  if not found then raise exception 'OPERATING_DAY_MISMATCH' using errcode='22023'; end if;
  select * into existing from public.inventory_ledger where id=entry_value for update;
  if found then
    if existing.stall_id is distinct from stall_value or existing.business_day_id is distinct from day_value
      or existing.product_id is distinct from product_value or existing.movement_type::text<>'pack_unsealed'
      or existing.sealed_packs_delta is distinct from -count_value or existing.quantity_delta<>quantity_value
      or existing.unit_cost is distinct from unit_cost_value
      or existing.occurred_at is distinct from (p_entry->>'occurred_at')::timestamptz then
      raise exception 'PACK_OPENING_IDEMPOTENCY_CONFLICT' using errcode='23505';
    end if;
    return jsonb_build_object('status','duplicate','ledger_id',entry_value);
  end if;
  insert into public.inventory_ledger(id,stall_id,business_day_id,product_id,quantity_delta,sealed_packs_delta,
    movement_type,reason,occurred_at,unit_cost)
  values(entry_value,stall_value,day_value,product_value,quantity_value,-count_value,'pack_unsealed','Powder pack opened',
    (p_entry->>'occurred_at')::timestamptz,unit_cost_value);
  update public.daily_store_closings c set cogs=c.cogs+cost_value, net_profit=c.net_profit-cost_value
    where c.business_day_id=day_value;
  return jsonb_build_object('status','accepted','ledger_id',entry_value);
end;
$$;

revoke all on function public.push_pos_powder_opening(jsonb) from public;
grant execute on function public.push_pos_powder_opening(jsonb) to anon,authenticated;

create or replace function public.push_pos_transaction_v2_unchecked(p_transaction jsonb)
returns jsonb language plpgsql security definer set search_path = public, private, extensions as $$
declare
  tx_id uuid := nullif(p_transaction->>'id','')::uuid;
  stall_id_value uuid := public.current_app_stall_id();
  device_id_value uuid := nullif(p_transaction->>'device_id','')::uuid;
  day_id_value uuid := nullif(p_transaction->>'business_day_id','')::uuid;
  receipt_value text := nullif(trim(p_transaction->>'receipt_number'),'');
  occurred_value timestamptz := nullif(p_transaction->>'occurred_at','')::timestamptz;
  gross_value numeric(12,2) := nullif(p_transaction->>'total_amount','')::numeric(12,2);
  cogs_value numeric(12,2);
  saved public.transactions%rowtype;
  upload private.pos_sale_uploads%rowtype;
  item_count integer;
  component_count integer;
begin
  if public.current_app_role()<>'cashier' then raise exception 'CASHIER_REQUIRED' using errcode='42501'; end if;
  if tx_id is null or stall_id_value is null or device_id_value is null or day_id_value is null
    or receipt_value is null or occurred_value is null
    or nullif(p_transaction->>'stall_id','')::uuid is distinct from stall_id_value then
    raise exception 'INVALID_TRANSACTION_IDENTITY' using errcode='22023';
  end if;
  if not public.active_pos_device(device_id_value) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode='42501';
  end if;
  if not exists(select 1 from public.business_days day where day.id=day_id_value
    and day.stall_id=stall_id_value and day.device_id=device_id_value and day.deleted_at is null
    and occurred_value>=day.opened_at and occurred_value<=coalesce(day.closed_at,'infinity'::timestamptz)) then
    raise exception 'OPERATING_DAY_MISMATCH' using errcode='22023';
  end if;

  select * into saved from public.transactions t where t.id=tx_id
    or (t.stall_id=stall_id_value and t.receipt_number=receipt_value)
    order by (t.id=tx_id) desc limit 1;
  if found then
    select * into upload from private.pos_sale_uploads u where u.transaction_id=saved.id;
    if saved.id<>tx_id or saved.stall_id<>stall_id_value
      or saved.receipt_number<>receipt_value or upload.payload is distinct from p_transaction then
      raise exception 'IDEMPOTENCY_CONFLICT' using errcode='23505';
    end if;
    return jsonb_build_object('status','duplicate','transaction_id',tx_id,
      'receipt_number',receipt_value,'inserted_items',0,'inserted_ledger',0);
  end if;

  if coalesce(p_transaction->>'status','completed')<>'completed'
    or jsonb_typeof(p_transaction->'items') is distinct from 'array'
    or jsonb_typeof(p_transaction->'components') is distinct from 'array' then
    raise exception 'SALE_LINES_REQUIRED' using errcode='22023';
  end if;
  if jsonb_array_length(p_transaction->'items')=0 then
    raise exception 'SALE_LINES_REQUIRED' using errcode='22023';
  end if;
  if gross_value is null or gross_value<0
    or nullif(p_transaction->>'subtotal','')::numeric is distinct from gross_value
    or nullif(p_transaction->>'cash_received','') is null
    or nullif(p_transaction->>'change_amount','') is null
    or nullif(p_transaction->>'cash_received','')::numeric < gross_value
    or nullif(p_transaction->>'change_amount','')::numeric
      is distinct from nullif(p_transaction->>'cash_received','')::numeric-gross_value
    or gross_value is distinct from (select sum((item->>'line_total')::numeric)
      from jsonb_array_elements(p_transaction->'items') item)
    or exists(select 1 from jsonb_array_elements(p_transaction->'items') item
      where nullif(item->>'id','') is null or nullif(item->>'product_id','') is null
        or nullif(trim(item->>'product_name'),'') is null
        or coalesce((item->>'quantity')::numeric,0)<=0
        or coalesce((item->>'unit_price')::numeric,-1)<0
        or (item->>'line_total')::numeric is distinct from
          round((item->>'quantity')::numeric*(item->>'unit_price')::numeric,2)) then
    raise exception 'TRANSACTION_TOTAL_MISMATCH' using errcode='22023';
  end if;
  if exists(select 1 from jsonb_array_elements(p_transaction->'components') component
    where nullif(component->>'id','') is null or nullif(component->>'product_id','') is null
      or coalesce((component->>'quantity')::numeric,0)<=0
      or coalesce((component->>'cost_total')::numeric,-1)<0)
    or (select count(distinct component->>'product_id') from jsonb_array_elements(p_transaction->'components') component)
      <> jsonb_array_length(p_transaction->'components') then
    raise exception 'INVALID_SALE_COMPONENT' using errcode='22023';
  end if;
  if exists(select 1 from jsonb_array_elements(p_transaction->'items') item
    left join public.products p on p.id=(item->>'product_id')::uuid and p.stall_id=stall_id_value
    where p.id is null or not p.is_sellable or (p.deleted_at is not null and p.deleted_at<=occurred_value)) then
    raise exception 'INVALID_PRODUCT' using errcode='22023';
  end if;
  if exists(select 1 from jsonb_array_elements(p_transaction->'components') component
    left join public.products p on p.id=(component->>'product_id')::uuid and p.stall_id=stall_id_value
    where p.id is null or (p.deleted_at is not null and p.deleted_at<=occurred_value)) then
    raise exception 'INVALID_SALE_COMPONENT' using errcode='22023';
  end if;

  -- Serialize sales consuming the same ingredient and check the snapshot,
  -- rather than the recipe currently configured when an offline sale arrives.
  perform 1 from public.products p
  join (select distinct (component->>'product_id')::uuid product_id
    from jsonb_array_elements(p_transaction->'components') component) used on used.product_id=p.id
  order by p.id for update of p;
  if exists(select 1 from jsonb_array_elements(p_transaction->'components') component
    where public.get_stock_on_hand(stall_id_value,(component->>'product_id')::uuid)
      < (component->>'quantity')::numeric) then
    raise exception 'INSUFFICIENT_STOCK' using errcode='P0001';
  end if;
  select coalesce(sum((component->>'cost_total')::numeric(12,2)),0) into cogs_value
  from jsonb_array_elements(p_transaction->'components') component;
  insert into public.transactions(id,stall_id,device_id,cashier_id,business_day_id,receipt_number,status,
    subtotal,total_amount,cash_received,change_amount,cogs,occurred_at)
  values(tx_id,stall_id_value,device_id_value,public.current_app_user_id(),day_id_value,receipt_value,'completed',
    gross_value,gross_value,(p_transaction->>'cash_received')::numeric(12,2),
    (p_transaction->>'change_amount')::numeric(12,2),cogs_value,occurred_value);
  insert into public.transaction_items(id,transaction_id,product_id,product_name,quantity,unit_price,line_total)
  select (item->>'id')::uuid,tx_id,(item->>'product_id')::uuid,item->>'product_name',
    (item->>'quantity')::numeric(12,3),(item->>'unit_price')::numeric(12,2),
    (item->>'line_total')::numeric(12,2) from jsonb_array_elements(p_transaction->'items') item;
  get diagnostics item_count=row_count;
  insert into public.inventory_ledger(id,stall_id,business_day_id,product_id,quantity_delta,movement_type,
    reason,reference_id,occurred_at,unit_cost)
  select (component->>'id')::uuid,stall_id_value,day_id_value,(component->>'product_id')::uuid,
    -(component->>'quantity')::numeric(12,3),'sale','offline checkout',tx_id,occurred_value,
    round((component->>'cost_total')::numeric/(component->>'quantity')::numeric,8)
  from jsonb_array_elements(p_transaction->'components') component;
  get diagnostics component_count=row_count;
  insert into public.sale_components(transaction_id,product_id,ledger_id,quantity,cost_total)
  select tx_id,(component->>'product_id')::uuid,(component->>'id')::uuid,
    (component->>'quantity')::numeric(12,3),(component->>'cost_total')::numeric(12,2)
  from jsonb_array_elements(p_transaction->'components') component;
  insert into private.pos_sale_uploads(transaction_id,stall_id,payload)
  values(tx_id,stall_id_value,p_transaction);
  return jsonb_build_object('status','accepted','transaction_id',tx_id,
    'receipt_number',receipt_value,'inserted_items',item_count,'inserted_ledger',component_count);
exception when unique_violation then
  select * into saved from public.transactions t where t.id=tx_id
    or (t.stall_id=stall_id_value and t.receipt_number=receipt_value)
    order by (t.id=tx_id) desc limit 1;
  select * into upload from private.pos_sale_uploads u where u.transaction_id=saved.id;
  if saved.id=tx_id and saved.stall_id=stall_id_value
    and saved.receipt_number=receipt_value and upload.payload=p_transaction then
    return jsonb_build_object('status','duplicate','transaction_id',tx_id,
      'receipt_number',receipt_value,'inserted_items',0,'inserted_ledger',0);
  end if;
  raise exception 'IDEMPOTENCY_CONFLICT' using errcode='23505';
end;
$$;

create or replace function public.push_daily_store_closing_v2(p_closing jsonb)
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
      + private.powder_opening_cost(day_value)
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
      coalesce(sum(case when status='completed' then cogs else 0 end),0) + private.powder_opening_cost(day_value),
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

create or replace function public.push_pos_sale_reversal(p_reversal jsonb)
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
  if sale.status<>'completed' or (not exists(select 1 from public.sale_components where transaction_id=transaction_value)
    and not exists(select 1 from private.pos_sale_uploads u where u.transaction_id=transaction_value
      and u.payload->'components'='[]'::jsonb)) then
    raise exception 'SALE_NOT_REVERSIBLE' using errcode='22023';
  end if;
  if jsonb_typeof(p_reversal->'movements') is distinct from 'array' then
    raise exception 'REVERSAL_MOVEMENTS_REQUIRED' using errcode='22023';
  end if;
  select count(*),count(distinct movement->>'product_id') into supplied_movement_count,movement_count
    from jsonb_array_elements(p_reversal->'movements') movement;
  if supplied_movement_count<>movement_count
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
    select coalesce(sum(total_amount),0),coalesce(sum(cogs),0) + private.powder_opening_cost(sale.business_day_id)
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
  select coalesce(sum(case
    when exists(select 1 from private.pos_sale_uploads u where u.transaction_id=t.id) then t.cogs
    else (select coalesce(sum(ti.quantity*p.cost_price),0)
      from public.transaction_items ti join public.products p on p.id=ti.product_id
      where ti.transaction_id=t.id and ti.deleted_at is null)
    end),0) + private.powder_opening_cost(day_row.id) into cogs
  from public.transactions t where t.stall_id=day_row.stall_id and t.status='completed'
    and t.deleted_at is null and (t.business_day_id=day_row.id or
      (t.business_day_id is null and (t.occurred_at at time zone 'Asia/Manila')::date=day_row.business_date));
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
