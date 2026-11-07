-- Disposable database only: photos must preserve the current powder/recipe contract.
begin;
set local search_path=public,private,extensions;
do $$
declare
  stall uuid:=gen_random_uuid(); other_stall uuid:=gen_random_uuid();
  admin_id uuid:=gen_random_uuid(); cashier_id uuid:=gen_random_uuid();
  admin_token text:=encode(gen_random_bytes(32),'hex');
  cashier_token text:=encode(gen_random_bytes(32),'hex');
  product jsonb; saved jsonb; product_id uuid; snapshot jsonb;
  photo text:='https://example.invalid/storage/v1/object/public/product-images/powder.png';
begin
  insert into public.stalls(id,name,code) values
    (stall,'Photo fixture','PHOTO-'||upper(substr(stall::text,1,8))),
    (other_stall,'Other photo fixture','PHOTO-'||upper(substr(other_stall::text,1,8)));
  insert into public.app_users(id,stall_id,email,employee_id,display_name,role,password_hash) values
    (admin_id,stall,admin_id::text||'@example.invalid',null,'Admin','system_admin','unused'),
    (cashier_id,stall,null,'PHOTO01','Cashier','cashier','unused');
  insert into public.app_sessions(user_id,token_hash,expires_at) values
    (admin_id,encode(digest(admin_token,'sha256'),'hex'),now()+interval '1 hour'),
    (cashier_id,encode(digest(cashier_token,'sha256'),'hex'),now()+interval '1 hour');
  insert into public.devices(stall_id,device_name,activation_code_hash,hardware_id)
    values(stall,'Photo POS','photo-activation','photo-hardware');
  insert into public.products(stall_id,sku,name,unit)
    values(other_stall,'FOREIGN-PHOTO','Foreign product','piece');
  perform set_config('request.headers',jsonb_build_object('x-session-token',admin_token)::text,true);
  product:=jsonb_build_object('stall_id',stall,'name','Powder with photo','unit','g',
    'product_type','raw','base_unit','g','sale_price',0,'cost_price',185,
    'purchase_unit_name','Pack','purchase_quantity',1000,'pack_size',1000,'conversion_rate',1,
    'low_stock_threshold',2000,'is_sellable',false,'tracks_powder_packs',true,'image_url',photo);
  saved:=public.save_product_with_recipe(product);
  product_id:=(saved->>'id')::uuid;
  if saved->>'image_url' is distinct from photo or not (saved->>'tracks_powder_packs')::boolean then
    raise exception 'Photo save lost image or powder tracking'; end if;
  product:=product||jsonb_build_object('id',product_id);
  saved:=public.save_product_with_recipe(product-'image_url');
  if saved->>'image_url' is distinct from photo then raise exception 'Legacy edit cleared photo'; end if;
  snapshot:=public.get_ims_financial_snapshot(stall);
  if not exists(select 1 from jsonb_array_elements(snapshot->'products') p
    where p->>'id'=product_id::text and p->>'image_url'=photo and (p->>'tracks_powder_packs')::boolean) then
    raise exception 'IMS snapshot lost photo or powder tracking'; end if;
  perform set_config('request.headers',jsonb_build_object('x-session-token',cashier_token,'x-device-id','photo-hardware')::text,true);
  if not exists(select 1 from public.get_pos_products_page() p where p.id=product_id
    and p.image_url=photo and p.tracks_powder_packs and p.product_type='raw' and p.base_unit='g'
    and p.cost_price=185) then raise exception 'Paged POS photo contract regressed'; end if;
  if exists(select 1 from public.get_pos_products_page() p where p.stall_id<>stall) then
    raise exception 'Paged photo catalog leaked another stall'; end if;
  if not exists(select 1 from public.get_pos_products() p where p.id=product_id
    and p.image_url=photo and p.tracks_powder_packs and p.product_type='raw') then
    raise exception 'Legacy photo catalog lost classification'; end if;
  begin
    perform public.save_product_with_recipe(product||jsonb_build_object('image_url',null));
    raise exception 'Cashier edited a product photo';
  exception when insufficient_privilege then null; end;
  perform set_config('request.headers',jsonb_build_object('x-session-token',admin_token)::text,true);
  saved:=public.save_product_with_recipe(product||jsonb_build_object('image_url',null));
  if saved->>'image_url' is not null then raise exception 'Explicit photo removal failed'; end if;
  perform set_config('request.headers','{}',true);
  begin
    perform public.get_pos_products_page();
    raise exception 'Anonymous photo catalog access accepted';
  exception when insufficient_privilege then null; end;
end;
$$;
rollback;
