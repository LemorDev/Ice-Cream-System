-- Run only on a disposable database or the explicitly verified development
-- project fhyqrgxwdqlyzxsnpthr. This transaction rolls back every test row.
-- Never run this file on production.
begin;
set local search_path = public, extensions;

do $$
declare
  stall_a uuid := gen_random_uuid();
  stall_b uuid := gen_random_uuid();
  cashier_a uuid := gen_random_uuid();
  cashier_b uuid := gen_random_uuid();
  owner_a uuid := gen_random_uuid();
  device_a uuid := gen_random_uuid();
  device_b uuid := gen_random_uuid();
  day_a uuid := gen_random_uuid();
  raw_a uuid := gen_random_uuid();
  serve_a uuid := gen_random_uuid();
  raw_b uuid := gen_random_uuid();
  sale_a uuid := gen_random_uuid();
  token_a text := encode(gen_random_bytes(32),'hex');
  token_b text := encode(gen_random_bytes(32),'hex');
  owner_token text := encode(gen_random_bytes(32),'hex');
  hardware_a text := 'r08-' || device_a::text;
  hardware_b text := 'r08-' || device_b::text;
  sale_payload jsonb;
  result jsonb;
begin
  if not has_function_privilege('anon','public.push_pos_transaction_v2(jsonb)','EXECUTE')
    or not has_function_privilege('anon','public.get_ims_financial_snapshot(uuid)','EXECUTE')
    or has_function_privilege('anon','public.push_pos_transaction(jsonb)','EXECUTE') then
    raise exception 'POS or IMS RPC grants differ from the candidate';
  end if;

  insert into public.stalls(id,name,code) values
    (stall_a,'R08 development A','R08A-'||substr(stall_a::text,1,8)),
    (stall_b,'R08 development B','R08B-'||substr(stall_b::text,1,8));
  insert into public.app_users(id,stall_id,email,display_name,role,password_hash) values
    (cashier_a,stall_a,cashier_a::text||'@example.invalid','R08 cashier A','cashier','unused'),
    (cashier_b,stall_b,cashier_b::text||'@example.invalid','R08 cashier B','cashier','unused'),
    (owner_a,stall_a,owner_a::text||'@example.invalid','R08 owner A','owner','unused');
  insert into public.owner_stall_access(user_id,stall_id) values(owner_a,stall_a);
  insert into public.app_sessions(user_id,token_hash,expires_at) values
    (cashier_a,encode(digest(token_a,'sha256'),'hex'),now()+interval '1 hour'),
    (cashier_b,encode(digest(token_b,'sha256'),'hex'),now()+interval '1 hour'),
    (owner_a,encode(digest(owner_token,'sha256'),'hex'),now()+interval '1 hour');
  insert into public.devices(id,stall_id,device_name,activation_code_hash,hardware_id) values
    (device_a,stall_a,'R08 device A','r08-'||device_a::text,hardware_a),
    (device_b,stall_b,'R08 device B','r08-'||device_b::text,hardware_b);
  insert into public.products(id,stall_id,sku,name,unit,cost_price,pack_size,
    conversion_rate,is_sellable,product_type,base_unit) values
    (raw_a,stall_a,'RAW','R08 ingredient A','g',50,100,1,false,'raw','g'),
    (serve_a,stall_a,'SERVE','R08 serving A','piece',0,1,1,true,'sellable','piece'),
    (raw_b,stall_b,'RAW','R08 ingredient B','g',50,100,1,false,'raw','g');
  insert into public.product_recipes(stall_id,parent_product_id,ingredient_product_id,quantity)
    values(stall_a,serve_a,raw_a,10);
  insert into public.business_days(id,stall_id,device_id,cashier_id,business_date,opened_at)
    values(day_a,stall_a,device_a,cashier_a,(now() at time zone 'Asia/Manila')::date,
      now()-interval '10 minutes');
  insert into public.inventory_ledger(stall_id,product_id,quantity_delta,movement_type)
    values(stall_a,raw_a,100,'opening_balance'),(stall_b,raw_b,100,'opening_balance');

  perform set_config('request.headers',jsonb_build_object(
    'x-session-token',token_a,'x-device-id',hardware_a)::text,true);
  if (select count(*) from public.get_pos_products_page(null,null,250))<>2
    or exists(select 1 from public.get_pos_products_page(null,null,250) p
      where p.stall_id<>stall_a or p.cost_price is null)
    or (select count(*) from public.get_pos_recipes_page(null,null,250))<>1
    or exists(select 1 from public.get_pos_inventory_ledger_page_v2(null,null,250) l
      where l.stall_id<>stall_a or l.unit_cost is null) then
    raise exception 'Cashier A POS pages leaked or omitted candidate fields';
  end if;

  sale_payload := jsonb_build_object('id',sale_a,'stall_id',stall_a,'device_id',device_a,
    'business_day_id',day_a,'receipt_number','R08-'||sale_a::text,
    'status','completed','subtotal',50,'total_amount',50,'cash_received',50,
    'change_amount',0,'occurred_at',now()-interval '1 minute',
    'items',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),
      'product_id',serve_a,'product_name','R08 serving A','quantity',1,
      'unit_price',50,'line_total',50)),
    'components',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),
      'product_id',raw_a,'quantity',10,'cost_total',5)));
  result := public.push_pos_transaction_v2(sale_payload);
  if result->>'status'<>'accepted' then raise exception 'POS sale rejected: %',result; end if;
  result := public.push_pos_transaction_v2(sale_payload);
  if result->>'status'<>'duplicate'
    or (select count(*) from public.transactions where id=sale_a)<>1
    or public.get_stock_on_hand(stall_a,raw_a)<>90 then
    raise exception 'Exact POS replay changed sales or stock';
  end if;
  begin
    perform public.push_pos_transaction_v2(sale_payload || jsonb_build_object('total_amount',51));
    raise exception 'Changed POS replay was accepted';
  exception when sqlstate '23505' then null; end;
  begin
    perform public.push_pos_transaction_v2(sale_payload || jsonb_build_object(
      'id',gen_random_uuid(),'stall_id',stall_b,'receipt_number','R08-CROSS-'||sale_a::text));
    raise exception 'Cross-stall POS upload was accepted';
  exception when sqlstate '22023' then null; end;
  begin
    perform public.get_ims_financial_snapshot(stall_a);
    raise exception 'Cashier received Owner financial data';
  exception when sqlstate '42501' then null; end;

  perform set_config('request.headers',jsonb_build_object(
    'x-session-token',token_b,'x-device-id',hardware_b)::text,true);
  if (select count(*) from public.get_pos_products_page(null,null,250))<>1
    or exists(select 1 from public.get_pos_products_page(null,null,250) p
      where p.stall_id<>stall_b) then
    raise exception 'Cashier B POS page leaked stall A';
  end if;
  begin
    perform public.push_pos_transaction_v2(sale_payload);
    raise exception 'Cashier B replayed stall A sale';
  exception when sqlstate '22023' then null; end;

  perform set_config('request.headers',jsonb_build_object('x-session-token',owner_token)::text,true);
  result := public.get_ims_financial_snapshot(stall_a);
  if jsonb_array_length(result->'transactions')<>1
    or jsonb_array_length(result->'transaction_items')<>1
    or jsonb_array_length(result->'sale_components')<>1
    or (result->'transactions'->0->>'business_day_id')::uuid<>day_a
    or (result->'transactions'->0->>'cogs')::numeric<>5 then
    raise exception 'Owner snapshot does not match POS sale and historical cost';
  end if;
  begin
    perform public.get_ims_financial_snapshot(stall_b);
    raise exception 'Owner A received stall B snapshot';
  exception when sqlstate '42501' then null; end;
  perform set_config('request.headers','{}',true);
  begin
    perform public.get_pos_products_page(null,null,250);
    raise exception 'Anonymous POS catalog pull was accepted';
  exception when sqlstate '42501' then null; end;
end;
$$;

select 'PASS: two stalls, cashier/Owner roles, POS RPC replay, and IMS snapshot' as r08_contract;
rollback;
