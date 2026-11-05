-- Run on a disposable database after all migrations; fixtures roll back.
begin;
set local search_path = public, extensions;

do $$
declare
  test_stall uuid := gen_random_uuid();
  test_admin uuid := gen_random_uuid();
  test_powder uuid := gen_random_uuid();
  test_menu uuid := gen_random_uuid();
  token text := encode(gen_random_bytes(32), 'hex');
  result jsonb;
begin
  insert into public.stalls (id, name, code)
  values (test_stall, 'Stock count fixture', 'COUNT-' || substr(test_stall::text, 1, 8));
  insert into public.app_users (id, stall_id, email, display_name, role, password_hash)
  values (test_admin, test_stall, test_admin::text || '@example.invalid', 'Admin', 'system_admin', 'unused');
  insert into public.app_sessions (user_id, token_hash, expires_at)
  values (test_admin, encode(digest(token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.products (id, stall_id, sku, name, unit, is_sellable, product_type, base_unit, pack_size)
  values
    (test_powder, test_stall, 'TEST-POWDER', 'Test powder', 'g', false, 'raw', 'g', 1000),
    (test_menu, test_stall, 'TEST-MENU', 'Test twirl', 'piece', true, 'sellable', 'piece', 1);
  insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type, reason)
  values (test_stall, test_powder, 10, 'receive', 'Old test entry');

  perform set_config('request.headers', jsonb_build_object('x-session-token', token)::text, true);
  result := public.set_stock_on_hand(test_stall, test_powder, 10000, 'Correct 10-pack test receipt');
  if result ->> 'status' <> 'adjusted' or (result ->> 'quantity_delta')::numeric <> 9990
    or public.get_stock_on_hand(test_stall, test_powder) <> 10000 then
    raise exception 'Physical count did not create the expected correction';
  end if;

  result := public.set_stock_on_hand(test_stall, test_powder, 10000, 'Repeat count');
  if result ->> 'status' <> 'unchanged' or (result ->> 'quantity_delta')::numeric <> 0 then
    raise exception 'Repeated physical count changed stock';
  end if;

  begin
    perform public.set_stock_on_hand(test_stall, test_menu, 10, 'Invalid menu stock');
    raise exception 'Made-to-order item accepted a physical stock count';
  exception when sqlstate '22023' then null;
  end;
end;
$$;

rollback;
