-- Run on a disposable database after all migrations.
begin;
set local search_path = public, extensions;

do $$
declare
  stall uuid := gen_random_uuid();
  other_stall uuid := gen_random_uuid();
  admin_id uuid := gen_random_uuid();
  owner_id uuid := gen_random_uuid();
  admin_token text := encode(gen_random_bytes(32), 'hex');
  owner_token text := encode(gen_random_bytes(32), 'hex');
  product_key uuid := gen_random_uuid();
  movement_key uuid := gen_random_uuid();
  count_key uuid := gen_random_uuid();
  sealed_key uuid := gen_random_uuid();
  failed_key uuid := gen_random_uuid();
  powder_id uuid;
  product_payload jsonb;
  movement_payload jsonb;
  count_payload jsonb;
  sealed_payload jsonb;
  original jsonb;
begin
  insert into public.stalls(id, name, code) values
    (stall, 'IMS replay', 'REPLAY-' || substr(stall::text, 1, 8)),
    (other_stall, 'Other stall', 'REPLAY-' || substr(other_stall::text, 1, 8));
  insert into public.app_users(id, stall_id, email, display_name, role, password_hash) values
    (admin_id, stall, admin_id::text || '@example.invalid', 'Admin', 'system_admin', 'unused'),
    (owner_id, stall, owner_id::text || '@example.invalid', 'Owner', 'owner', 'unused');
  insert into public.app_sessions(user_id, token_hash, expires_at) values
    (admin_id, encode(digest(admin_token, 'sha256'), 'hex'), now() + interval '1 hour'),
    (owner_id, encode(digest(owner_token, 'sha256'), 'hex'), now() + interval '1 hour');

  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  product_payload := jsonb_build_object('product', jsonb_build_object(
    'name', 'Vanilla powder', 'unit', 'g', 'product_type', 'raw', 'base_unit', 'g',
    'sale_price', 0, 'cost_price', 185, 'low_stock_threshold', 0, 'purchase_unit_name', 'Pack', 'purchase_quantity', 1000,
    'pack_size', 1000, 'conversion_rate', 1, 'is_sellable', false, 'tracks_powder_packs', true
  ), 'recipe', '[]'::jsonb);
  powder_id := (public.submit_ims_change(gen_random_uuid(), stall, 'create_product', product_payload)->>'id')::uuid;
  product_payload := jsonb_build_object('product', jsonb_build_object(
    'name', 'Vanilla cup', 'unit', 'piece', 'product_type', 'sellable', 'base_unit', 'piece',
    'sale_price', 40, 'cost_price', 4.63, 'low_stock_threshold', 0, 'is_sellable', true, 'sell_category', 'Cup'
  ), 'recipe', jsonb_build_array(jsonb_build_object('ingredient_product_id', powder_id, 'quantity', 25)));
  original := public.submit_ims_change(product_key, stall, 'create_product', product_payload);
  if public.submit_ims_change(product_key, stall, 'create_product', product_payload) is distinct from original
    or (select count(*) from public.products where stall_id = stall) <> 2
    or (select count(*) from public.product_recipes where parent_product_id = (original->>'id')::uuid) <> 1 then
    raise exception 'Product retry did not preserve the original product and recipe';
  end if;

  movement_payload := jsonb_build_object('product_id', powder_id, 'quantity_delta', 1000,
    'movement_type', 'receive', 'reason', 'Powder delivery');
  original := public.submit_ims_change(movement_key, stall, 'stock_movement', movement_payload);
  if public.submit_ims_change(movement_key, stall, 'stock_movement', movement_payload) is distinct from original
    or public.get_stock_on_hand(stall, powder_id) <> 1000 then
    raise exception 'Delivery retry changed inventory';
  end if;
  perform public.submit_ims_change(gen_random_uuid(), stall, 'stock_movement', movement_payload);
  if public.get_stock_on_hand(stall, powder_id) <> 2000 then
    raise exception 'Distinct matching deliveries were merged';
  end if;
  begin
    perform public.submit_ims_change(movement_key, stall, 'stock_movement', movement_payload || '{"quantity_delta":2000}');
    raise exception 'Changed movement was accepted';
  exception when unique_violation then
    if sqlerrm <> 'IMS_IDEMPOTENCY_CONFLICT' then raise; end if;
  end;
  begin
    perform public.submit_ims_change(movement_key, other_stall, 'stock_movement', movement_payload);
    raise exception 'Same key moved to another stall';
  exception when unique_violation then
    if sqlerrm <> 'IMS_IDEMPOTENCY_CONFLICT' then raise; end if;
  end;

  count_payload := jsonb_build_object('product_id', powder_id, 'target_stock', 4000, 'reason', 'Counted stock');
  original := public.submit_ims_change(count_key, stall, 'stock_count', count_payload);
  perform public.submit_ims_change(gen_random_uuid(), stall, 'stock_movement', movement_payload);
  if public.submit_ims_change(count_key, stall, 'stock_count', count_payload) is distinct from original
    or public.get_stock_on_hand(stall, powder_id) <> 5000 then
    raise exception 'Count retry overwrote an intervening delivery';
  end if;

  sealed_payload := jsonb_build_object('product_id', powder_id, 'count', 1, 'reason', 'Counted sealed packs');
  original := public.submit_ims_change(sealed_key, stall, 'sealed_pack_count', sealed_payload);
  perform public.submit_ims_change(gen_random_uuid(), stall, 'sealed_pack_count', sealed_payload || '{"count":2}');
  if public.submit_ims_change(sealed_key, stall, 'sealed_pack_count', sealed_payload) is distinct from original
    or (select sum(sealed_packs_delta) from public.inventory_ledger where product_id = powder_id) <> 2 then
    raise exception 'Sealed count retry overwrote a newer count';
  end if;

  begin
    perform public.submit_ims_change(failed_key, stall, 'stock_movement',
      movement_payload || '{"movement_type":"adjustment","quantity_delta":-6000}');
    raise exception 'A negative stock balance was accepted';
  exception when invalid_parameter_value then null;
  end;
  perform public.submit_ims_change(failed_key, stall, 'stock_movement', movement_payload);
  if public.get_stock_on_hand(stall, powder_id) <> 6000 then
    raise exception 'Failed request left writes or blocked a corrected retry';
  end if;

  perform set_config('request.headers', jsonb_build_object('x-session-token', owner_token)::text, true);
  begin
    perform public.submit_ims_change(movement_key, stall, 'stock_movement', movement_payload);
    raise exception 'Owner was allowed to submit or retrieve a protected receipt';
  exception when insufficient_privilege then null;
  end;
  if has_table_privilege('anon', 'private.ims_submissions', 'SELECT,INSERT,UPDATE,DELETE')
    or has_table_privilege('authenticated', 'private.ims_submissions', 'SELECT,INSERT,UPDATE,DELETE') then
    raise exception 'API roles have direct access to stored submission receipts';
  end if;
end;
$$;

rollback;
