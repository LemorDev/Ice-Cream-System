-- Disposable database only. Exercises the current IMS/POS powder RPC contract.
begin;
set local search_path=public,private,extensions;
do $$
declare
  v_stall uuid:=gen_random_uuid(); v_admin uuid:=gen_random_uuid(); v_cashier uuid:=gen_random_uuid();
  v_device uuid:=gen_random_uuid(); v_day uuid:=gen_random_uuid(); v_powder uuid; v_menu uuid:=gen_random_uuid();
  v_admin_token text:=encode(gen_random_bytes(32),'hex'); v_cashier_token text:=encode(gen_random_bytes(32),'hex');
  v_product jsonb; v_saved jsonb; v_open jsonb; v_receipt jsonb; v_sale jsonb; v_snapshot jsonb; v_resume jsonb;
  v_open_id uuid:=gen_random_uuid(); v_sale_id uuid:=gen_random_uuid(); v_count integer; v_total numeric;
begin
  insert into public.stalls(id,name,code) values(v_stall,'Powder fixture','POWDER-'||substr(v_stall::text,1,8));
  insert into public.app_users(id,stall_id,email,employee_id,display_name,role,password_hash) values
    (v_admin,v_stall,v_admin::text||'@example.invalid',null,'Admin','system_admin','unused'),
    (v_cashier,v_stall,null,'POWDER01','Cashier','cashier','unused');
  insert into public.app_sessions(user_id,token_hash,expires_at) values
    (v_admin,encode(digest(v_admin_token,'sha256'),'hex'),now()+interval '1 hour'),
    (v_cashier,encode(digest(v_cashier_token,'sha256'),'hex'),now()+interval '1 hour');
  insert into public.devices(id,stall_id,device_name,activation_code_hash,hardware_id)
    values(v_device,v_stall,'Powder POS','unused','powder-hardware');
  insert into public.business_days(id,stall_id,device_id,cashier_id,business_date,opened_at)
    values(v_day,v_stall,v_device,v_cashier,(now() at time zone 'Asia/Manila')::date,now()-interval '1 hour');
  perform set_config('request.headers',jsonb_build_object('x-session-token',v_admin_token)::text,true);
  v_product:=jsonb_build_object('stall_id',v_stall,'name','Vanilla powder','unit','g','product_type','raw',
    'base_unit','g','sale_price',0,'cost_price',185,'purchase_unit_name','Pack','purchase_quantity',1000,
    'pack_size',1000,'conversion_rate',1,'low_stock_threshold',2000,'is_sellable',false,'tracks_powder_packs',true);
  v_saved:=public.save_product_with_recipe(v_product);
  v_powder:=(v_saved->>'id')::uuid;
  if not (v_saved->>'tracks_powder_packs')::boolean or (v_saved->>'low_stock_threshold')::numeric<>2000 then
    raise exception 'Powder product save lost tracking or gram threshold'; end if;
  v_product:=v_product||jsonb_build_object('id',v_powder);
  insert into public.inventory_ledger(stall_id,product_id,quantity_delta,movement_type)
    values(v_stall,v_powder,3000,'opening_balance');
  perform public.set_powder_sealed_packs(v_powder,3);
  begin
    perform public.save_product_with_recipe(v_product||jsonb_build_object('tracks_powder_packs',false));
    raise exception 'Tracking disabled while sealed packs remained';
  exception when sqlstate '22023' then null; end;
  begin
    perform public.set_powder_sealed_packs(v_powder,4);
    raise exception 'Count exceeded the available total powder';
  exception when sqlstate 'P0001' then
    if sqlerrm<>'INSUFFICIENT_OPERATIONAL_POWDER' then raise; end if;
  end;
  v_snapshot:=public.get_ims_financial_snapshot(v_stall);
  if not exists(select 1 from jsonb_array_elements(v_snapshot->'products') p
    where p->>'id'=v_powder::text and (p->>'tracks_powder_packs')::boolean) then
    raise exception 'IMS snapshot omitted powder tracking'; end if;

  insert into public.products(id,stall_id,sku,name,unit,sale_price,is_sellable,product_type,base_unit)
    values(v_menu,v_stall,'POWDER-MENU','Vanilla serving','piece',40,true,'sellable','piece');
  perform set_config('request.headers',jsonb_build_object('x-session-token',v_cashier_token,'x-device-id','powder-hardware')::text,true);
  if not exists(select 1 from public.get_pos_products_page() p where p.id=v_powder and p.tracks_powder_packs) then
    raise exception 'POS page omitted powder tracking'; end if;
  if public.get_recovered_pos_day(v_device) is not null then
    raise exception 'Ordinary active phone unexpectedly returned a recovered day'; end if;
  v_resume:=public.get_resumable_pos_day(v_device);
  if v_resume->>'id'<>v_day::text or (v_resume->>'known_sales')::numeric<>0
    or (v_resume->>'known_orders')::integer<>0 then
    raise exception 'Same-device day resume did not return the open day'; end if;
  v_sale:=jsonb_build_object('id',v_sale_id,'stall_id',v_stall,'device_id',v_device,'business_day_id',v_day,
    'receipt_number','POWDER-'||v_sale_id::text,'status','completed','subtotal',40,'total_amount',40,
    'cash_received',40,'change_amount',0,'occurred_at',now(),
    'items',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',v_menu,'product_name','Vanilla serving',
      'quantity',1,'unit_price',40,'line_total',40)),
    'components',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',v_powder,'quantity',45,'cost_total',8.33)));
  if public.push_pos_transaction_v2(v_sale)->>'status'<>'accepted' then raise exception 'Negative hopper sale was rejected'; end if;
  v_resume:=public.get_resumable_pos_day(v_device);
  if (v_resume->>'known_sales')::numeric<>40 or (v_resume->>'known_orders')::integer<>1
    or (v_resume->>'known_cogs')::numeric<>8.33 then
    raise exception 'Same-device day resume lost server-known sale totals'; end if;
  select sum(quantity_delta),sum(sealed_packs_delta) into v_total,v_count from public.inventory_ledger where product_id=v_powder;
  if v_total<>2955 or v_count<>3 or v_total-v_count*1000<>-45 then raise exception 'Sale did not consume operational grams'; end if;
  v_open:=jsonb_build_object('id',v_open_id,'stall_id',v_stall,'business_day_id',v_day,
    'product_id',v_powder,'packs_opened',1,'occurred_at',now());
  if public.push_pos_pack_unseal(v_open)->>'status'<>'accepted'
    or public.push_pos_pack_unseal(v_open)->>'status'<>'duplicate' then raise exception 'Opening retry was not idempotent'; end if;
  select sum(quantity_delta),sum(sealed_packs_delta) into v_total,v_count from public.inventory_ledger where product_id=v_powder;
  if v_total<>2955 or v_count<>2 or v_total-v_count*1000<>955 then raise exception 'Opening changed total or incorrect hopper balance'; end if;
  begin
    perform public.push_pos_pack_unseal(v_open||jsonb_build_object('packs_opened',2));
    raise exception 'Opening accepted changed retry';
  exception when sqlstate '23505' then null; end;
  begin
    perform public.push_pos_pack_unseal(v_open||jsonb_build_object('id',gen_random_uuid(),'packs_opened',3));
    raise exception 'Opening exceeded available sealed packs';
  exception when sqlstate 'P0001' then
    if sqlerrm<>'INSUFFICIENT_SEALED_PACKS' then raise; end if;
  end;
  v_receipt:=jsonb_build_object('id',gen_random_uuid(),'stall_id',v_stall,'business_day_id',v_day,
    'product_id',v_powder,'quantity_delta',2000,'movement_type','receive','reason','Powder delivery','occurred_at',now());
  if public.push_pos_inventory_entry_v2(v_receipt)->>'status'<>'accepted'
    or public.push_pos_inventory_entry_v2(v_receipt)->>'status'<>'duplicate' then raise exception 'Powder receipt retry failed'; end if;
  select sum(quantity_delta),sum(sealed_packs_delta) into v_total,v_count from public.inventory_ledger where product_id=v_powder;
  if v_total<>4955 or v_count<>4 then raise exception 'Powder receipt count or total incorrect'; end if;
  if not exists(select 1 from public.get_pos_inventory_ledger_page_v2() l where l.id=v_open_id and l.sealed_packs_delta=-1) then
    raise exception 'POS ledger page lost opening delta'; end if;
  -- A sale cannot exceed total powder even when missing opening logs are tolerated.
  begin
    perform public.push_pos_transaction_v2(v_sale||jsonb_build_object('id',gen_random_uuid(),'receipt_number','TOO-MUCH',
      'components',jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'product_id',v_powder,'quantity',5000,'cost_total',925))));
    raise exception 'Sale allowed total powder to go negative';
  exception when sqlstate 'P0001' then
    if sqlerrm<>'INSUFFICIENT_STOCK' then raise; end if;
  end;
  perform set_config('request.headers','{}',true);
  begin
    perform public.push_pos_pack_unseal(v_open||jsonb_build_object('id',gen_random_uuid()));
    raise exception 'Anonymous pack opening was accepted';
  exception when insufficient_privilege then null; end;
  perform set_config('request.headers',jsonb_build_object('x-session-token',v_admin_token)::text,true);
  perform public.set_powder_sealed_packs(v_powder,0);
  v_saved:=public.save_product_with_recipe(v_product||jsonb_build_object('tracks_powder_packs',false));
  if (v_saved->>'tracks_powder_packs')::boolean then raise exception 'Tracking could not be disabled after reconciliation'; end if;
end;
$$;
rollback;
