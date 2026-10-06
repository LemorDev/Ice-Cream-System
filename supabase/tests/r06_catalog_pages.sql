-- Disposable database only; validates equal-timestamp keyset pages and stall scope.
begin;
set local search_path = public, extensions;
do $$
<<fixture>>
declare
  stall_id uuid := gen_random_uuid();
  other_stall uuid := gen_random_uuid();
  cashier_id uuid := gen_random_uuid();
  parent_id uuid := gen_random_uuid();
  token text := encode(gen_random_bytes(32),'hex');
  page_at timestamptz;
  page_id uuid;
begin
  insert into public.stalls(id,name,code) values
    (stall_id,'Paged stall','PAGE-'||substr(stall_id::text,1,8)),
    (other_stall,'Other stall','OTHER-'||substr(other_stall::text,1,8));
  insert into public.app_users(id,stall_id,email,display_name,role,password_hash)
  values(cashier_id,stall_id,cashier_id::text||'@example.invalid','Cashier','cashier','unused');
  insert into public.app_sessions(user_id,token_hash,expires_at)
  values(cashier_id,encode(digest(token,'sha256'),'hex'),now()+interval '1 hour');
  insert into public.devices(stall_id,device_name,activation_code_hash,hardware_id)
  values(stall_id,'Paged POS','unused','r06-page-device');
  insert into public.products(id,stall_id,sku,name,unit,cost_price,is_sellable,product_type,base_unit)
  values(parent_id,stall_id,'PARENT','Serve','piece',0,true,'sellable','piece');
  insert into public.products(stall_id,sku,name,unit,cost_price,is_sellable,product_type,base_unit)
  select stall_id,'RAW-'||n,'Ingredient '||n,'piece',2.5,false,'raw','piece'
  from generate_series(1,251) n;
  insert into public.products(stall_id,sku,name,unit,is_sellable,product_type,base_unit)
  values(other_stall,'OTHER','Other','piece',false,'raw','piece');
  insert into public.product_recipes(stall_id,parent_product_id,ingredient_product_id,quantity)
  select fixture.stall_id,parent_id,p.id,1 from public.products p where p.stall_id=fixture.stall_id and p.id<>parent_id;
  insert into public.inventory_ledger(stall_id,product_id,quantity_delta,movement_type)
  select stall_id,parent_id,1,'opening_balance' from generate_series(1,251);
  insert into public.inventory_ledger(stall_id,product_id,quantity_delta,movement_type)
  values(other_stall,(select p.id from public.products p where p.stall_id=other_stall),1,'opening_balance');
  perform set_config('request.headers',jsonb_build_object('x-session-token',token,'x-device-id','r06-page-device')::text,true);

  if (select count(*) from public.get_pos_products_page(null,null,250)) <> 250 then
    raise exception 'Product first page was truncated';
  end if;
  select p.updated_at,p.id into page_at,page_id from public.get_pos_products_page(null,null,250) p
    order by p.updated_at desc,p.id desc limit 1;
  if (select count(*) from public.get_pos_products_page(page_at,page_id,250)) <> 2 then
    raise exception 'Equal-time products were skipped';
  end if;
  if not exists(select 1 from public.get_pos_products_page(null,null,250) p where p.cost_price=2.5) then
    raise exception 'POS product page omitted unit cost';
  end if;
  select r.updated_at,r.id into page_at,page_id from public.get_pos_recipes_page(null,null,250) r
    order by r.updated_at desc,r.id desc limit 1;
  if (select count(*) from public.get_pos_recipes_page(page_at,page_id,250)) <> 1 then
    raise exception 'Equal-time recipes were skipped';
  end if;
  select l.updated_at,l.id into page_at,page_id from public.get_pos_inventory_ledger_page(null,null,250) l
    order by l.updated_at desc,l.id desc limit 1;
  if (select count(*) from public.get_pos_inventory_ledger_page(page_at,page_id,250)) <> 1 then
    raise exception 'Equal-time ledger rows were skipped';
  end if;
  if exists(select 1 from public.get_pos_inventory_ledger_page(null,null,500) l where l.stall_id=other_stall) then
    raise exception 'Other stall ledger leaked';
  end if;
  perform set_config('request.headers','{}',true);
  begin
    perform public.get_pos_products_page(null,null,250);
    raise exception 'Anonymous catalog pull was accepted';
  exception when sqlstate '42501' then null; end;
end;
$$;
rollback;
