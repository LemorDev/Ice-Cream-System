-- Disposable database only. Apply migrations first; fixtures roll back.
begin;
set local search_path = public, extensions;
do $$
declare
  stall uuid := gen_random_uuid(); other_stall uuid := gen_random_uuid();
  cashier uuid := gen_random_uuid(); raw_item uuid := gen_random_uuid();
  menu_item uuid := gen_random_uuid(); packaging uuid := gen_random_uuid();
  token text := encode(gen_random_bytes(32), 'hex');
  sale_id uuid := gen_random_uuid();
  payload jsonb; response jsonb; bad jsonb;
begin
  insert into public.stalls(id, name, code) values
    (stall, 'Receiving fixture', 'RCV-' || substr(stall::text,1,8)),
    (other_stall, 'Other fixture', 'RCV-' || substr(other_stall::text,1,8));
  insert into public.app_users(id, stall_id, email, display_name, role, password_hash)
    values(cashier, stall, cashier::text || '@example.invalid', 'Cashier', 'cashier', 'unused');
  insert into public.app_sessions(user_id, token_hash, expires_at)
    values(cashier, encode(digest(token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.devices(stall_id, device_name, activation_code_hash, hardware_id)
    values(stall, 'Receiving POS', 'unused', 'receiving-test-device');
  insert into public.products(id, stall_id, sku, name, unit, is_sellable, product_type, base_unit) values
    (raw_item, stall, 'RAW', 'Powder', 'g', false, 'raw', 'g'),
    (packaging, stall, 'CUP', 'Cups', 'piece', false, 'packaging', 'piece'),
    (menu_item, stall, 'MENU', 'Sundae', 'piece', true, 'sellable', 'piece');
  perform set_config('request.headers', jsonb_build_object('x-session-token',token,'x-device-id','receiving-test-device')::text,true);
  payload := jsonb_build_object('id',gen_random_uuid(),'stall_id',stall,'product_id',raw_item,
    'quantity_delta',12.125,'movement_type','receive','occurred_at',now(),'reason','Delivery 1');
  response := public.push_pos_inventory_entry(payload);
  if response->>'status' <> 'accepted' then raise exception 'Receipt rejected'; end if;
  response := public.push_pos_inventory_entry(payload);
  if response->>'status' <> 'duplicate' or public.get_stock_on_hand(stall,raw_item) <> 12.125 then
    raise exception 'Retry counted twice'; end if;
  perform public.push_pos_inventory_entry(payload || jsonb_build_object('id',gen_random_uuid()));
  if public.get_stock_on_hand(stall,raw_item) <> 24.25 then raise exception 'Separate delivery was lost'; end if;
  perform public.push_pos_inventory_entry(payload || jsonb_build_object('id',gen_random_uuid(),'product_id',packaging,'quantity_delta',10));
  if public.get_stock_on_hand(stall,packaging) <> 10 then raise exception 'Packaging receipt failed'; end if;
  -- Exercise the enum comparison used by the existing sale duplicate path too.
  insert into public.transactions(id, stall_id, cashier_id, receipt_number, subtotal, total_amount)
    values(sale_id, stall, cashier, sale_id::text, 10, 10);
  bad := jsonb_build_object('id',gen_random_uuid(),'stall_id',stall,'product_id',raw_item,
    'quantity_delta',-1,'movement_type','sale','reference_id',sale_id,'occurred_at',now());
  response := public.push_pos_inventory_entry(bad);
  if response->>'status' <> 'accepted' or public.get_stock_on_hand(stall,raw_item) <> 23.25 then
    raise exception 'Sale movement regression'; end if;
  response := public.push_pos_inventory_entry(bad || jsonb_build_object('id',gen_random_uuid()));
  if response->>'status' <> 'duplicate' or public.get_stock_on_hand(stall,raw_item) <> 23.25 then
    raise exception 'Sale duplicate regression'; end if;
  foreach bad in array array[
    jsonb_build_object('quantity_delta',0), jsonb_build_object('quantity_delta',-1),
    jsonb_build_object('quantity_delta',0.0001), jsonb_build_object('quantity_delta','NaN'),
    jsonb_build_object('quantity_delta',1000000000), jsonb_build_object('quantity_delta',null),
    jsonb_build_object('product_id',menu_item), jsonb_build_object('product_id',gen_random_uuid()),
    jsonb_build_object('reference_id',gen_random_uuid())
  ] loop
    begin
      perform public.push_pos_inventory_entry(payload || bad || jsonb_build_object('id',gen_random_uuid()));
      raise exception 'Invalid receipt accepted: %',bad;
    exception when sqlstate '22023' then null; end;
  end loop;
  begin
    perform public.push_pos_inventory_entry(payload || jsonb_build_object('stall_id',other_stall));
    raise exception 'Wrong stall accepted';
  exception when sqlstate '42501' then null; end;
  update public.products set deleted_at = now() where id = raw_item;
  begin
    perform public.push_pos_inventory_entry(payload || jsonb_build_object('id',gen_random_uuid()));
    raise exception 'Deleted product accepted';
  exception when sqlstate '22023' then null; end;
  perform set_config('request.headers', jsonb_build_object('x-session-token',token,'x-device-id','unknown')::text,true);
  begin
    perform public.push_pos_inventory_entry(payload);
    raise exception 'Unknown device accepted';
  exception when sqlstate '42501' then null; end;
  perform set_config('request.headers','{}',true);
  begin
    perform public.push_pos_inventory_entry(payload);
    raise exception 'Anonymous receipt accepted';
  exception when sqlstate '42501' then null; end;
end;
$$;
rollback;
