-- Run against a disposable local database after applying every migration.
-- Fixtures and mutations are rolled back; no application credentials are needed.
begin;
set local search_path = public, extensions;

do $$
declare
  own_stall uuid := gen_random_uuid();
  other_stall uuid := gen_random_uuid();
  own_user uuid := gen_random_uuid();
  own_cashier uuid := gen_random_uuid();
  other_user uuid := gen_random_uuid();
  own_product uuid := gen_random_uuid();
  other_product uuid := gen_random_uuid();
  waste_tx uuid := gen_random_uuid();
  restock_tx uuid := gen_random_uuid();
  other_tx uuid := gen_random_uuid();
  pushed_tx uuid := gen_random_uuid();
  owner_token text := encode(gen_random_bytes(32), 'hex');
  cashier_token text := encode(gen_random_bytes(32), 'hex');
  other_token text := encode(gen_random_bytes(32), 'hex');
  device_id uuid := gen_random_uuid();
  day_id uuid := gen_random_uuid();
  hardware_id text := 'sale-regression-device';
  response jsonb;
  payload jsonb;
begin
  insert into public.stalls (id, name, code) values
    (own_stall, 'Sale regression fixture', own_stall::text),
    (other_stall, 'Other regression fixture', other_stall::text);
  insert into public.app_users (id, stall_id, email, display_name, role, password_hash) values
    (own_user, own_stall, own_user::text || '@example.invalid', 'Owner fixture', 'owner', 'unused'),
    (own_cashier, own_stall, own_cashier::text || '@example.invalid', 'Cashier fixture', 'cashier', 'unused'),
    (other_user, other_stall, other_user::text || '@example.invalid', 'Other fixture', 'owner', 'unused');
  insert into public.app_sessions (user_id, token_hash, expires_at) values
    (own_user, encode(digest(owner_token, 'sha256'), 'hex'), now() + interval '1 hour'),
    (own_cashier, encode(digest(cashier_token, 'sha256'), 'hex'), now() + interval '1 hour'),
    (other_user, encode(digest(other_token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.owner_stall_access (user_id, stall_id) values
    (own_user, own_stall),
    (other_user, other_stall);
  insert into public.devices (id, stall_id, device_name, activation_code_hash, hardware_id)
  values (device_id, own_stall, 'Regression POS', encode(digest(gen_random_bytes(32), 'sha256'), 'hex'), hardware_id);
  insert into public.products (id, stall_id, sku, name, unit) values
    (own_product, own_stall, 'TEST-OWN', 'Product fixture', 'piece'),
    (other_product, other_stall, 'TEST-OTHER', 'Other product fixture', 'piece');
  insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type) values
    (own_stall, own_product, 10, 'opening_balance'),
    (other_stall, other_product, 10, 'opening_balance');
  insert into public.transactions (id, stall_id, cashier_id, receipt_number, subtotal, total_amount) values
    (waste_tx, own_stall, own_cashier, waste_tx::text, 150, 150),
    (restock_tx, own_stall, own_cashier, restock_tx::text, 100, 100),
    (other_tx, other_stall, null, other_tx::text, 50, 50);
  insert into public.transaction_items (transaction_id, product_id, product_name, quantity, unit_price, line_total) values
    (waste_tx, own_product, 'Product fixture', 3, 50, 150),
    (restock_tx, own_product, 'Product fixture', 2, 50, 100),
    (other_tx, other_product, 'Other product fixture', 1, 50, 50);

  -- SECURITY DEFINER helpers must reject requests without an application session.
  perform set_config('request.headers', '{}', true);
  if public.get_stock_on_hand(own_stall, own_product) <> 0
    or public.get_low_stock_threshold(own_product) is not null then
    raise exception 'Unauthenticated inventory details were exposed';
  end if;
  begin
    perform public.post_sale_inventory_ledger(waste_tx);
    raise exception 'Unauthenticated sale posting was accepted';
  exception when invalid_authorization_specification then null;
  end;
  begin
    perform public.reverse_sale_inventory_ledger(waste_tx, 'Quality issue', false);
    raise exception 'Unauthenticated reversal was accepted';
  exception when invalid_authorization_specification then null;
  end;

  -- A valid owner session must not post or reverse another stall's transaction.
  perform set_config('request.headers', jsonb_build_object('x-session-token', owner_token)::text, true);
  if public.get_stock_on_hand(other_stall, other_product) <> 0
    or public.get_low_stock_threshold(other_product) is not null then
    raise exception 'Another stall inventory details were exposed';
  end if;
  if public.get_stock_on_hand(own_stall, own_product) <> 10
    or public.get_low_stock_threshold(own_product) <> 0 then
    raise exception 'Own-stall inventory details are unavailable';
  end if;
  begin
    perform public.post_sale_inventory_ledger(other_tx);
    raise exception 'Cross-stall sale posting was accepted';
  exception when no_data_found then null;
  end;
  begin
    perform public.reverse_sale_inventory_ledger(other_tx, 'Incorrect order', true);
    raise exception 'Cross-stall reversal was accepted';
  exception when no_data_found then null;
  end;

  begin
    perform public.reverse_sale_inventory_ledger(restock_tx, 'Incorrect order', true);
    raise exception 'An unposted sale was allowed to create stock';
  exception when invalid_parameter_value then null;
  end;

  if public.post_sale_inventory_ledger(waste_tx) <> 1
    or public.post_sale_inventory_ledger(restock_tx) <> 1
    or public.post_sale_inventory_ledger(waste_tx) <> 0 then
    raise exception 'Sale posting or retry returned an unexpected count';
  end if;
  if public.get_stock_on_hand(own_stall, own_product) <> 5 then
    raise exception 'Posting did not deduct the exact sale quantities';
  end if;

  begin
    perform public.reverse_sale_inventory_ledger(waste_tx, '  ', false);
    raise exception 'A reversal without a reason was accepted';
  exception when invalid_parameter_value then null;
  end;
  update public.transactions set status = 'refunded' where id = restock_tx;
  begin
    perform public.reverse_sale_inventory_ledger(restock_tx, 'Incorrect order', true);
    raise exception 'A reversal of an ineligible transaction was accepted';
  exception when invalid_parameter_value then null;
  end;
  update public.transactions set status = 'completed' where id = restock_tx;

  if public.reverse_sale_inventory_ledger(waste_tx, 'Quality issue', false) <> 1 then
    raise exception 'Waste reversal did not record its ledger marker';
  end if;
  if public.get_stock_on_hand(own_stall, own_product) <> 5 then
    raise exception 'Waste reversal changed available stock';
  end if;
  if not exists (
    select 1 from public.inventory_ledger
    where reference_id = waste_tx and product_id = own_product
      and movement_type = 'void_waste' and quantity_delta = 0
  ) or (select status from public.transactions where id = waste_tx) <> 'voided' then
    raise exception 'Waste reversal did not preserve its reference and voided status';
  end if;
  if public.reverse_sale_inventory_ledger(waste_tx, 'Quality issue', false) <> 0
    or public.reverse_sale_inventory_ledger(waste_tx, 'Incorrect order', true) <> 0 then
    raise exception 'Waste reversal was applied more than once';
  end if;

  -- Cashiers retain access to their own sale/reversal helpers.
  -- Restock must reverse the recorded deduction even if a sale item has changed.
  update public.transaction_items set quantity = 9 where transaction_id = restock_tx;
  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token)::text, true);
  if public.reverse_sale_inventory_ledger(restock_tx, 'Incorrect order', true) <> 1
    or public.reverse_sale_inventory_ledger(restock_tx, 'Incorrect order', true) <> 0
    or public.reverse_sale_inventory_ledger(restock_tx, 'Quality issue', false) <> 0 then
    raise exception 'Restock reversal or its retries returned unexpected counts';
  end if;
  if public.get_stock_on_hand(own_stall, own_product) <> 7 then
    raise exception 'Restock did not restore exactly the sold quantity';
  end if;

  -- Non-waste movements must still reject zero quantities.
  begin
    insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type)
    values (own_stall, own_product, 0, 'adjustment');
    raise exception 'A zero-quantity adjustment was accepted';
  exception when check_violation then null;
  end;

  -- The Android RPC calls the secured posting helper in the same session.
  perform set_config('request.headers', jsonb_build_object(
    'x-session-token', cashier_token, 'x-device-id', hardware_id
  )::text, true);
  insert into public.business_days (id, stall_id, device_id, cashier_id, business_date, opened_at)
  values (day_id, own_stall, device_id, own_cashier, (now() at time zone 'Asia/Manila')::date, now() - interval '1 hour');
  payload := jsonb_build_object(
    'id', pushed_tx, 'stall_id', own_stall, 'device_id', device_id, 'receipt_number', pushed_tx::text,
    'status', 'completed', 'subtotal', 50, 'total_amount', 50,
    'cash_received', 100, 'change_amount', 50, 'occurred_at', now(),
    'items', jsonb_build_array(jsonb_build_object(
      'id', gen_random_uuid(), 'product_id', own_product, 'product_name', 'Product fixture',
      'quantity', 1, 'unit_price', 50, 'line_total', 50
    ))
  );
  response := public.push_pos_transaction(payload);
  if response ->> 'status' <> 'accepted' or response ->> 'transaction_id' <> pushed_tx::text then
    raise exception 'Android sale push did not return its existing acceptance contract';
  end if;
  response := public.push_pos_transaction(payload);
  if response ->> 'status' <> 'duplicate' or public.get_stock_on_hand(own_stall, own_product) <> 6 then
    raise exception 'Android retry changed stock or did not acknowledge the duplicate';
  end if;

  -- Previously rejected foreign transactions remain available to their own session.
  perform set_config('request.headers', jsonb_build_object('x-session-token', other_token)::text, true);
  if public.post_sale_inventory_ledger(other_tx) <> 1 then
    raise exception 'Another stall could not post its own sale';
  end if;
  if not has_function_privilege('anon', 'public.post_sale_inventory_ledger(uuid,text)', 'EXECUTE')
    or not has_function_privilege('anon', 'public.reverse_sale_inventory_ledger(uuid,text,boolean)', 'EXECUTE') then
    raise exception 'Custom-session clients lost access to the sale RPCs';
  end if;
end;
$$;

rollback;
