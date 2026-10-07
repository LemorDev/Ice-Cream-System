-- Disposable database only: one operating day crosses Manila midnight while
-- an offline recipe and cost are edited before the second sale uploads.
begin;
set local search_path = public, extensions;
do $$ begin
  if has_function_privilege('authenticated','public.push_pos_transaction(jsonb)','EXECUTE')
    or has_function_privilege('authenticated','public.push_daily_store_closing(jsonb)','EXECUTE')
    or has_function_privilege('authenticated','public.push_revenue_deduction(jsonb)','EXECUTE')
    or has_function_privilege('authenticated','public.push_pos_inventory_entry(jsonb)','EXECUTE')
    or has_function_privilege('authenticated','public.reverse_sale_inventory_ledger(uuid,text,boolean)','EXECUTE')
    or not has_function_privilege('authenticated','public.push_pos_transaction_v2(jsonb)','EXECUTE')
    or not has_function_privilege('authenticated','public.push_pos_sale_reversal(jsonb)','EXECUTE') then
    raise exception 'Legacy write permissions or v2 grants are unsafe';
  end if;
end $$;
do $$
declare
  fixture_stall uuid := gen_random_uuid();
  fixture_cashier uuid := gen_random_uuid();
  fixture_owner uuid := gen_random_uuid();
  fixture_device uuid := gen_random_uuid();
  fixture_day uuid := gen_random_uuid();
  payout_day uuid := gen_random_uuid();
  utc_crossing_day uuid := gen_random_uuid();
  powder uuid := gen_random_uuid();
  serve uuid := gen_random_uuid();
  sale_one uuid := gen_random_uuid();
  sale_two uuid := gen_random_uuid();
  sale_three uuid := gen_random_uuid();
  late_sale uuid := gen_random_uuid();
  closing_id uuid := gen_random_uuid();
  receipt_id uuid := gen_random_uuid();
  deduction_id uuid := gen_random_uuid();
  token text := encode(gen_random_bytes(32),'hex');
  owner_token text := encode(gen_random_bytes(32),'hex');
  payload jsonb;
  sale_payload jsonb;
  reversal_payload jsonb;
  response jsonb;
begin
  insert into public.stalls(id,name,code) values(fixture_stall,'Snapshot stall','SNAP-'||substr(fixture_stall::text,1,8));
  insert into public.app_users(id,stall_id,email,display_name,role,password_hash)
  values(fixture_cashier,fixture_stall,fixture_cashier::text||'@example.invalid','Cashier','cashier','unused');
  insert into public.app_sessions(user_id,token_hash,expires_at)
  values(fixture_cashier,encode(digest(token,'sha256'),'hex'),now()+interval '1 hour');
  insert into public.devices(id,stall_id,device_name,activation_code_hash,hardware_id)
  values(fixture_device,fixture_stall,'Snapshot POS','unused','r06-snapshot-device');
  insert into public.products(id,stall_id,sku,name,unit,cost_price,pack_size,conversion_rate,is_sellable,product_type,base_unit)
  values(powder,fixture_stall,'POWDER','Powder','g',500,1000,1,false,'raw','g'),
    (serve,fixture_stall,'SERVE','Serve','piece',0,1,1,true,'sellable','piece');
  insert into public.product_recipes(stall_id,parent_product_id,ingredient_product_id,quantity)
  values(fixture_stall,serve,powder,80);
  insert into public.inventory_ledger(stall_id,product_id,quantity_delta,movement_type)
  values(fixture_stall,powder,200,'opening_balance');
  insert into public.business_days(id,stall_id,device_id,cashier_id,business_date,opened_at,closed_at)
  values(fixture_day,fixture_stall,fixture_device,fixture_cashier,'2026-08-04',
    '2026-08-04T15:55:00Z','2026-08-04T16:20:00Z');
  perform set_config('request.headers',jsonb_build_object('x-session-token',token,'x-device-id','r06-snapshot-device')::text,true);
  payload := jsonb_build_object('id',sale_one,'stall_id',fixture_stall,'device_id',fixture_device,
    'business_day_id',fixture_day,'receipt_number','LOCAL-'||sale_one::text,'status','completed',
    'subtotal',50,'total_amount',50,'cash_received',50,'change_amount',0,
    'occurred_at','2026-08-04T15:58:00Z',
    'items',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',serve,
      'product_name','Serve','quantity',1,'unit_price',50,'line_total',50)),
    'components',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',powder,
      'quantity',80,'cost_total',40)));
  response := public.push_pos_transaction_v2(payload);
  if response->>'status'<>'accepted' then raise exception 'First snapshot sale rejected: %',response; end if;
  response := public.push_pos_transaction_v2(payload);
  if response->>'status'<>'duplicate' or public.get_stock_on_hand(fixture_stall,powder)<>120 then
    raise exception 'Snapshot replay changed stock';
  end if;
  update public.product_recipes set quantity=100 where parent_product_id=serve;
  update public.products set cost_price=600 where id=powder;
  payload := payload || jsonb_build_object('id',sale_two,'receipt_number','LOCAL-'||sale_two::text,
    'occurred_at','2026-08-04T16:05:00Z',
    'items',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',serve,
      'product_name','Serve','quantity',1,'unit_price',50,'line_total',50)),
    'components',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',powder,
      'quantity',80,'cost_total',40)));
  response := public.push_pos_transaction_v2(payload);
  if response->>'status'<>'accepted' or public.get_stock_on_hand(fixture_stall,powder)<>40 then
    raise exception 'Offline recipe snapshot was not used';
  end if;
  sale_payload := payload;
  if (select count(*) from public.transactions t where t.business_day_id=fixture_day and t.cogs=40)<>2
    or (select count(*) from public.inventory_ledger l where l.reference_id in (sale_one,sale_two)
      and l.quantity_delta=-80 and l.unit_cost=0.5 and l.business_day_id=fixture_day)<>2 then
    raise exception 'Day or cost snapshot changed after catalog edit';
  end if;
  begin
    perform public.push_pos_transaction_v2(payload || jsonb_build_object('id',gen_random_uuid()));
    raise exception 'Equal-total receipt collision was accepted';
  exception when sqlstate '23505' then null; end;
  if public.get_stock_on_hand(fixture_stall,powder)<>40 then raise exception 'Collision changed stock'; end if;
  payload := jsonb_build_object('id',receipt_id,'stall_id',fixture_stall,'business_day_id',fixture_day,
    'product_id',powder,'quantity_delta',10,'movement_type','receive','reason','Delivery',
    'occurred_at','2026-08-04T16:10:00Z');
  response := public.push_pos_inventory_entry_v2(payload);
  if response->>'status'<>'accepted' then raise exception 'Day-bound receipt failed'; end if;
  response := public.push_pos_inventory_entry_v2(payload);
  if response->>'status'<>'duplicate' or public.get_stock_on_hand(fixture_stall,powder)<>50 then
    raise exception 'Day-bound receipt replay changed stock'; end if;
  begin
    perform public.push_pos_inventory_entry_v2(payload || jsonb_build_object('quantity_delta',11));
    raise exception 'Changed receipt replay was accepted';
  exception when sqlstate '23505' then null; end;
  payload := jsonb_build_object('id',deduction_id,'stall_id',fixture_stall,
    'business_day_id',fixture_day,'business_date','2026-08-04',
    'amount',10,'reason','Cash transfer','cashier_id',fixture_cashier,
    'affects_profit',false,'occurred_at','2026-08-04T16:06:00Z');
  response := public.push_revenue_deduction_v2(payload);
  if response->>'status'<>'accepted' then raise exception 'Overnight day-bound deduction failed'; end if;
  response := public.push_revenue_deduction_v2(payload);
  if response->>'status'<>'duplicate' then raise exception 'Deduction replay failed'; end if;
  begin
    perform public.push_revenue_deduction_v2(payload || jsonb_build_object('amount',11));
    raise exception 'Changed deduction replay was accepted';
  exception when sqlstate '23505' then null; end;
  begin
    perform public.push_daily_store_closing_v2(jsonb_build_object('id',closing_id,
      'stall_id',fixture_stall,'business_day_id',fixture_day,'business_date','2026-08-04',
      'device_id',fixture_device,'closed_at','2026-08-04T16:20:00Z',
      'sale_count',1,'movement_count',3,'deduction_count',1,'reversal_count',0,
      'gross_sales',100,'cogs',80,'waste_cost',0,'revenue_deduction',10,
      'expected_cash',90,'collected_cash',90));
    raise exception 'Closing accepted a missing sale';
  exception when sqlstate '22023' then null; end;
  response := public.push_daily_store_closing_v2(jsonb_build_object('id',closing_id,
    'stall_id',fixture_stall,'business_day_id',fixture_day,'business_date','2026-08-04',
    'device_id',fixture_device,'closed_at','2026-08-04T16:20:00Z',
    'sale_count',2,'movement_count',3,'deduction_count',1,'reversal_count',0,
    'gross_sales',100,'cogs',80,'waste_cost',0,'revenue_deduction',10,
    'expected_cash',90,'collected_cash',90));
  if response->>'status'<>'accepted' or (select gross_sales from public.daily_store_closings where id=closing_id)<>100
    then raise exception 'Reconciled closing was not posted'; end if;
  response := public.push_pos_transaction_v2(sale_payload);
  if response->>'status'<>'duplicate' then raise exception 'Sale retry after closing failed'; end if;
  insert into public.business_days(id,stall_id,device_id,cashier_id,business_date,opened_at,closed_at)
  values(payout_day,fixture_stall,fixture_device,fixture_cashier,'2026-08-05',
    '2026-08-05T02:00:00Z','2026-08-05T03:00:00Z');
  reversal_payload := jsonb_build_object('id',gen_random_uuid(),'stall_id',fixture_stall,
    'transaction_id',sale_one,'payout_day_id',payout_day,'kind','refund',
    'reason','Customer return','restock',true,'cash_returned',50,
    'occurred_at','2026-08-05T02:05:00Z',
    'movements',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',powder)));
  response := public.push_pos_sale_reversal(reversal_payload);
  if response->>'status'<>'accepted' then raise exception 'After-close refund failed'; end if;
  response := public.push_pos_sale_reversal(reversal_payload);
  if response->>'status'<>'duplicate' then raise exception 'Refund replay failed'; end if;
  if (select status from public.transactions where id=sale_one)<>'refunded'
    or (select gross_sales from public.daily_store_closings where id=closing_id)<>50
    or (select cogs from public.daily_store_closings where id=closing_id)<>40
    or (select expected_cash from public.daily_store_closings where id=closing_id)<>90
    or (select count(*) from public.inventory_ledger
      where reference_id=sale_one and movement_type='void_restock')<>1 then
    raise exception 'Original day, till snapshot, or stock did not reconcile after refund';
  end if;
  begin
    perform public.push_pos_sale_reversal(reversal_payload || jsonb_build_object('reason','Changed'));
    raise exception 'Changed refund replay was accepted';
  exception when sqlstate '23505' then null; end;
  response := public.push_daily_store_closing_v2(jsonb_build_object('id',gen_random_uuid(),
    'stall_id',fixture_stall,'business_day_id',payout_day,'business_date','2026-08-05',
    'device_id',fixture_device,'closed_at','2026-08-05T03:00:00Z',
    'sale_count',0,'movement_count',0,'deduction_count',0,'reversal_count',1,
    'gross_sales',0,'cogs',0,'waste_cost',0,'revenue_deduction',0,
    'expected_cash',-50,'collected_cash',0));
  if response->>'status'<>'accepted' then raise exception 'Refund payout day did not close'; end if;
  insert into public.business_days(id,stall_id,device_id,cashier_id,business_date,opened_at,closed_at)
  values(utc_crossing_day,fixture_stall,fixture_device,fixture_cashier,'2026-08-06',
    '2026-08-05T23:55:00Z','2026-08-06T00:20:00Z');
  payload := sale_payload || jsonb_build_object('id',sale_three,'receipt_number','LOCAL-'||sale_three::text,
    'business_day_id',utc_crossing_day,'occurred_at','2026-08-06T00:03:00Z',
    'items',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',serve,
      'product_name','Serve','quantity',1,'unit_price',50,'line_total',50)),
    'components',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',powder,
      'quantity',10,'cost_total',5)));
  response := public.push_pos_transaction_v2(payload);
  if response->>'status'<>'accepted'
    or (select business_day_id from public.transactions where id=sale_three)<>utc_crossing_day then
    raise exception 'UTC midnight changed operating-day identity';
  end if;
  reversal_payload := jsonb_build_object('id',gen_random_uuid(),'stall_id',fixture_stall,
    'transaction_id',sale_three,'payout_day_id',utc_crossing_day,'kind','void',
    'reason','Unpaid entry mistake','restock',false,'cash_returned',0,
    'occurred_at','2026-08-06T00:10:00Z',
    'movements',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',powder)));
  response := public.push_pos_sale_reversal(reversal_payload);
  if response->>'status'<>'accepted' or (select status from public.transactions where id=sale_three)<>'voided'
    or (select count(*) from public.inventory_ledger where reference_id=sale_three
      and movement_type='void_waste' and quantity_delta=0)<>1 then
    raise exception 'Unpaid waste void did not post exactly once';
  end if;
  response := public.push_daily_store_closing_v2(jsonb_build_object('id',gen_random_uuid(),
    'stall_id',fixture_stall,'business_day_id',utc_crossing_day,'business_date','2026-08-06',
    'device_id',fixture_device,'closed_at','2026-08-06T00:20:00Z',
    'sale_count',1,'movement_count',2,'deduction_count',0,'reversal_count',1,
    'gross_sales',0,'cogs',0,'waste_cost',5,'revenue_deduction',0,
    'expected_cash',0,'collected_cash',0));
  if response->>'status'<>'accepted' then raise exception 'Waste void did not reconcile in closing'; end if;
  payload := sale_payload || jsonb_build_object('id',late_sale,'receipt_number','LOCAL-'||late_sale::text,
    'occurred_at','2026-08-04T16:15:00Z',
    'items',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',serve,
      'product_name','Serve','quantity',1,'unit_price',50,'line_total',50)),
    'components',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',powder,
      'quantity',10,'cost_total',5)));
  response := public.push_pos_transaction_v2(payload);
  if response->>'status'<>'accepted'
    or (select gross_sales from public.daily_store_closings where id=closing_id)<>100
    or (select cogs from public.daily_store_closings where id=closing_id)<>45
    or (select expected_cash from public.daily_store_closings where id=closing_id)<>140
    or (select collected_cash from public.daily_store_closings where id=closing_id)<>90
    or (select count(*) from public.closed_day_corrections where transaction_id=late_sale)<>1 then
    raise exception 'Late sale did not correct the closed day with cash variance';
  end if;
  response := public.push_pos_transaction_v2(payload);
  if response->>'status'<>'duplicate' or
    (select count(*) from public.closed_day_corrections where transaction_id=late_sale)<>1 then
    raise exception 'Late sale replay duplicated a correction';
  end if;
  begin
    perform public.get_ims_financial_snapshot(fixture_stall);
    raise exception 'Cashier saw Owner financial snapshot';
  exception when sqlstate '42501' then null; end;
  insert into public.app_users(id,stall_id,email,display_name,role,password_hash)
  values(fixture_owner,fixture_stall,fixture_owner::text||'@example.invalid','Owner','owner','unused');
  insert into public.owner_stall_access(user_id,stall_id) values(fixture_owner,fixture_stall);
  insert into public.app_sessions(user_id,token_hash,expires_at)
  values(fixture_owner,encode(digest(owner_token,'sha256'),'hex'),now()+interval '1 hour');
  perform set_config('request.headers',jsonb_build_object('x-session-token',owner_token)::text,true);
  response := public.get_ims_financial_snapshot(fixture_stall);
  if jsonb_array_length(response->'transactions')<>4
    or jsonb_array_length(response->'sale_components')<>4
    or jsonb_array_length(response->'sale_reversals')<>2
    or jsonb_array_length(response->'revenue_deductions')<>1
    or jsonb_array_length(response->'closed_day_corrections')<>1 then
    raise exception 'Owner snapshot is incomplete';
  end if;
  begin
    perform public.get_ims_financial_snapshot(gen_random_uuid());
    raise exception 'Owner saw another stall snapshot';
  exception when sqlstate '42501' then null; end;
end;
$$;
rollback;
