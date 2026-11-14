-- Run against a disposable local database after all migrations.
-- This transaction rolls back every fixture row.
begin;
set local search_path = public, extensions;

do $$
declare
  stall_a uuid := gen_random_uuid();
  stall_b uuid := gen_random_uuid();
  cashier_a uuid := gen_random_uuid();
  cashier_b uuid := gen_random_uuid();
  owner_a uuid := gen_random_uuid();
  admin_a uuid := gen_random_uuid();
  device_a uuid := gen_random_uuid();
  device_b uuid := gen_random_uuid();
  day_a uuid := gen_random_uuid();
  raw_a uuid := gen_random_uuid();
  serve_a uuid := gen_random_uuid();
  large_cup uuid;
  flavor_a uuid := gen_random_uuid();
  raw_b uuid := gen_random_uuid();
  sale_a uuid := gen_random_uuid();
  expired_sale_id uuid := gen_random_uuid();
  token_a text := encode(gen_random_bytes(32),'hex');
  token_b text := encode(gen_random_bytes(32),'hex');
  owner_token text := encode(gen_random_bytes(32),'hex');
  admin_token text := encode(gen_random_bytes(32),'hex');
  hardware_a text := 'r08-' || device_a::text;
  hardware_b text := 'r08-' || device_b::text;
  sale_payload jsonb;
  revision_id uuid;
  foreign_revision_id uuid;
  rejected record;
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
    (owner_a,stall_a,owner_a::text||'@example.invalid','R08 owner A','owner','unused'),
    (admin_a,stall_a,admin_a::text||'@example.invalid','R08 admin A','system_admin','unused');
  insert into public.owner_stall_access(user_id,stall_id) values(owner_a,stall_a);
  insert into public.app_sessions(user_id,token_hash,expires_at) values
    (cashier_a,encode(digest(token_a,'sha256'),'hex'),now()+interval '1 hour'),
    (cashier_b,encode(digest(token_b,'sha256'),'hex'),now()+interval '1 hour'),
    (owner_a,encode(digest(owner_token,'sha256'),'hex'),now()+interval '1 hour'),
    (admin_a,encode(digest(admin_token,'sha256'),'hex'),now()+interval '1 hour');
  insert into public.devices(id,stall_id,device_name,activation_code_hash,hardware_id) values
    (device_a,stall_a,'R08 device A','r08-'||device_a::text,hardware_a),
    (device_b,stall_b,'R08 device B','r08-'||device_b::text,hardware_b);
  insert into public.products(id,stall_id,sku,name,unit,sale_price,cost_price,pack_size,
    conversion_rate,is_sellable,product_type,base_unit) values
    (raw_a,stall_a,'RAW','R08 ingredient A','g',0,50,100,1,false,'raw','g'),
    (serve_a,stall_a,'SERVE','R08 serving A','piece',50,0,1,1,true,'sellable','piece'),
    (raw_b,stall_b,'RAW','R08 ingredient B','g',0,50,100,1,false,'raw','g');
  insert into public.product_recipes(stall_id,parent_product_id,ingredient_product_id,quantity)
    values(stall_a,serve_a,raw_a,10);
  insert into public.business_days(id,stall_id,device_id,cashier_id,business_date,opened_at)
    values(day_a,stall_a,device_a,cashier_a,(now() at time zone 'Asia/Manila')::date,
      now()-interval '10 minutes');
  insert into public.inventory_ledger(stall_id,product_id,quantity_delta,movement_type)
    values(stall_a,raw_a,100,'opening_balance'),(stall_b,raw_b,100,'opening_balance');

  perform set_config('request.headers',jsonb_build_object(
    'x-session-token',token_b,'x-device-id',hardware_b)::text,true);
  foreign_revision_id := (public.get_pos_catalog_revision()->>'revision_id')::uuid;
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

  revision_id := (public.get_pos_catalog_revision()->>'revision_id')::uuid;
  -- Offline checkout keeps the approved price even after the live catalog changes.
  update public.products set sale_price=75 where id=serve_a;
  sale_payload := jsonb_build_object('id',sale_a,'stall_id',stall_a,'device_id',device_a,
    'catalog_revision_id',revision_id,
    'business_day_id',day_a,'receipt_number','R08-'||sale_a::text,
    'status','completed','subtotal',50,'total_amount',50,'cash_received',50,
    'change_amount',0,'occurred_at',now()-interval '1 minute',
    'items',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),
      'product_id',serve_a,'product_name','R08 serving A','quantity',1,
      'unit_price',50,'line_total',50)),
    'components',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),
      'product_id',raw_a,'quantity',10,'cost_total',5)));
  for rejected in select * from (values
    (sale_payload - 'catalog_revision_id', 'SALE_CATALOG_REVISION_REQUIRED'),
    (sale_payload || jsonb_build_object('catalog_revision_id',gen_random_uuid()), 'SALE_CATALOG_REVISION_REQUIRED'),
    (sale_payload || jsonb_build_object('catalog_revision_id',foreign_revision_id), 'SALE_CATALOG_REVISION_REQUIRED'),
    (jsonb_set(sale_payload,'{components,0,cost_total}','0'), 'SALE_CATALOG_COMPONENT_MISMATCH'),
    (jsonb_set(sale_payload,'{components}','[]'), 'SALE_CATALOG_COMPONENT_MISMATCH'),
    (jsonb_set(jsonb_set(sale_payload,'{items,0,unit_price}','1'),'{items,0,line_total}','1')
      || '{"subtotal":1,"total_amount":1,"cash_received":1}'::jsonb, 'SALE_CATALOG_PRICE_MISMATCH')
  ) as invalid_sale(payload, expected_message) loop
    begin
      perform public.push_pos_transaction_v2(rejected.payload);
      raise exception 'Tampered sale was accepted';
    exception when invalid_parameter_value then
      if sqlerrm <> rejected.expected_message then raise; end if;
    end;
  end loop;
  if exists(select 1 from public.transactions where id=sale_a)
    or exists(select 1 from public.transaction_items where transaction_id=sale_a)
    or exists(select 1 from private.pos_sale_uploads where transaction_id=sale_a)
    or public.get_stock_on_hand(stall_a,raw_a)<>100 then
    raise exception 'Rejected sale left financial or inventory changes';
  end if;
  result := public.push_pos_transaction_v2(sale_payload);
  if result->>'status'<>'accepted' then raise exception 'POS sale rejected: %',result; end if;
  update private.pos_catalog_revisions set valid_until=now()-interval '1 second' where id=revision_id;
  begin
    perform public.push_pos_transaction_v2(
      jsonb_set(jsonb_set(sale_payload,'{items,0,id}',to_jsonb(gen_random_uuid())),
        '{components,0,id}',to_jsonb(gen_random_uuid()))
      || jsonb_build_object('id',expired_sale_id,'receipt_number','R08-EXPIRED-'||expired_sale_id::text));
    raise exception 'A new sale used an expired revision';
  exception when invalid_parameter_value then
    if sqlerrm <> 'SALE_CATALOG_REVISION_REQUIRED' then raise; end if;
  end;
  if exists(select 1 from public.transactions where id=expired_sale_id)
    or exists(select 1 from public.transaction_items where transaction_id=expired_sale_id)
    or exists(select 1 from private.pos_sale_uploads where transaction_id=expired_sale_id)
    or public.get_stock_on_hand(stall_a,raw_a)<>90 then
    raise exception 'An expired revision left financial or inventory changes';
  end if;
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

  insert into public.product_categories(id,stall_id,name) values(flavor_a,stall_a,'Oreo');
  update public.products set menu_group='Soft serve', serving_type='cup', serving_size='6 oz',
    category_id=flavor_a, sale_price=40 where id=serve_a;
  perform set_config('request.headers',jsonb_build_object('x-session-token',admin_token)::text,true);
  result := public.save_product_with_recipe(jsonb_build_object(
    'stall_id',stall_a,'category_id',flavor_a,'sell_category','Cup','menu_group','Soft serve',
    'serving_type','cup','serving_size','12 oz','name','12 oz Oreo','unit','piece',
    'sale_price',80,'cost_price',10,'low_stock_threshold',0,
    'is_sellable',true,'product_type','sellable','base_unit','piece'),
    jsonb_build_array(jsonb_build_object('ingredient_product_id',raw_a,'quantity',20)));
  large_cup := (result->>'id')::uuid;
  perform set_config('request.headers',jsonb_build_object('x-session-token',owner_token)::text,true);
  if not exists (select 1 from jsonb_array_elements(public.get_ims_financial_snapshot(stall_a)->'products') p
    where (p->>'id')::uuid=large_cup and p->>'serving_size'='12 oz' and (p->>'sale_price')::numeric=80) then
    raise exception 'IMS did not return the saved size and price';
  end if;

  perform set_config('request.headers',jsonb_build_object(
    'x-session-token',token_a,'x-device-id',hardware_a)::text,true);
  result := public.get_pos_catalog_revision();
  if (result->>'add_on_price_cents')::int is distinct from 500 then
    raise exception 'Catalog did not advertise the approved add-on price';
  end if;
  if not exists (select 1 from jsonb_array_elements(result->'products') p
    where (p->>'id')::uuid=large_cup and p->>'menu_group'='Soft serve'
      and p->>'flavor_name'='Oreo' and p->>'serving_size'='12 oz') then
    raise exception 'POS catalog did not include the saved size and flavor';
  end if;
  sale_payload := sale_payload || jsonb_build_object(
    'id',expired_sale_id,'receipt_number','R08-ADDON-'||expired_sale_id::text,
    'catalog_revision_id',result->>'revision_id',
    'subtotal',125,'total_amount',125,'cash_received',150,'change_amount',25,
    'items',jsonb_build_array(
      jsonb_build_object('id',gen_random_uuid(),'product_id',serve_a,
        'product_name','Soft serve · 6 oz cup · Oreo','quantity',1,'unit_price',40,'line_total',40),
      jsonb_build_object('id',gen_random_uuid(),'product_id',large_cup,
        'product_name','Soft serve · 12 oz cup · Oreo + Add-on','quantity',1,'unit_price',85,'line_total',85)),
    'components',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),
      'product_id',raw_a,'quantity',30,'cost_total',15)));
  for rejected in select * from (values
    (jsonb_set(sale_payload,'{items,1,product_name}','"R08 serving A"'), 'SALE_CATALOG_PRICE_MISMATCH'),
    (jsonb_set(jsonb_set(sale_payload,'{items,1,unit_price}','45'),'{items,1,line_total}','45')
      || '{"subtotal":85,"total_amount":85,"change_amount":65}'::jsonb, 'SALE_CATALOG_PRICE_MISMATCH')
  ) as invalid_add_on(payload, expected_message) loop
    begin
      perform public.push_pos_transaction_v2(rejected.payload);
      raise exception 'An unauthorized add-on price was accepted';
    exception when invalid_parameter_value then
      if sqlerrm <> rejected.expected_message then raise; end if;
    end;
  end loop;
  result := public.push_pos_transaction_v2(sale_payload);
  if result->>'status'<>'accepted'
    or (select total_amount from public.transactions where id=expired_sale_id)<>125
    or (select count(*) from public.transaction_items where transaction_id=expired_sale_id)<>2
    or public.get_stock_on_hand(stall_a,raw_a)<>60 then
    raise exception 'Mixed add-on sale did not save the correct receipt or stock';
  end if;
  result := public.push_pos_transaction_v2(sale_payload);
  if result->>'status'<>'duplicate' or public.get_stock_on_hand(stall_a,raw_a)<>60 then
    raise exception 'Replaying an add-on sale deducted stock again';
  end if;
  perform set_config('request.headers','{}',true);
  begin
    perform public.get_pos_products_page(null,null,250);
    raise exception 'Anonymous POS catalog pull was accepted';
  exception when sqlstate '42501' then null; end;
end;
$$;

select 'PASS: two stalls, cashier/Owner roles, POS RPC replay, and IMS snapshot' as r08_contract;
rollback;
