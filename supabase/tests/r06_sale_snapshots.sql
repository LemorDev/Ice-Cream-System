-- Disposable database only: one operating day crosses Manila midnight while
-- an offline recipe and cost are edited before the second sale uploads.
begin;
set local search_path = public, extensions;
do $$
declare
  fixture_stall uuid := gen_random_uuid();
  fixture_cashier uuid := gen_random_uuid();
  fixture_device uuid := gen_random_uuid();
  fixture_day uuid := gen_random_uuid();
  powder uuid := gen_random_uuid();
  serve uuid := gen_random_uuid();
  sale_one uuid := gen_random_uuid();
  sale_two uuid := gen_random_uuid();
  token text := encode(gen_random_bytes(32),'hex');
  payload jsonb;
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
end;
$$;
rollback;
