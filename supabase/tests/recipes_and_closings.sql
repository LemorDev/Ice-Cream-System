-- Run against a disposable database after every migration. All fixtures roll back.
begin;
set local search_path = public, extensions;

do $$
declare
  stall_id uuid := gen_random_uuid();
  cashier_id uuid := gen_random_uuid();
  device_id uuid := gen_random_uuid();
  powder_id uuid := gen_random_uuid();
  cone_id uuid := gen_random_uuid();
  serve_id uuid := gen_random_uuid();
  tx_id uuid := gen_random_uuid();
  day_id uuid := gen_random_uuid();
  token text := encode(gen_random_bytes(32), 'hex');
  close_id uuid := gen_random_uuid();
  response jsonb;
begin
  insert into public.stalls (id, name, code, overhead_config) values
    (stall_id, 'Recipe fixture', 'RECIPE-' || substr(stall_id::text, 1, 8), '[{"key":"rent","label":"Rent","dailyRate":10}]');
  insert into public.app_users (id, stall_id, email, display_name, role, password_hash)
    values (cashier_id, stall_id, cashier_id::text || '@example.invalid', 'Cashier', 'cashier', 'unused');
  insert into public.app_sessions (user_id, token_hash, expires_at)
    values (cashier_id, encode(digest(token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.devices (id, stall_id, device_name, activation_code_hash, hardware_id)
    values (device_id, stall_id, 'Recipe POS', 'unused', 'recipe-test-device');
  insert into public.products (id, stall_id, sku, name, unit, cost_price, pack_size, conversion_rate, is_sellable, product_type, base_unit) values
    (powder_id, stall_id, 'POWDER', 'Powder', 'g', 500, 1000, 1, false, 'raw', 'g'),
    (cone_id, stall_id, 'CONE', 'Cone', 'piece', 100, 50, 1, false, 'packaging', 'piece'),
    (serve_id, stall_id, 'SERVE', 'Vanilla cone', 'piece', 42, 1, 1, true, 'sellable', 'piece');
  insert into public.product_recipes (stall_id, parent_product_id, ingredient_product_id, quantity) values
    (stall_id, serve_id, powder_id, 80), (stall_id, serve_id, cone_id, 1);
  insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type) values
    (stall_id, powder_id, 1000, 'opening_balance'), (stall_id, cone_id, 20, 'opening_balance');
  insert into public.business_days (id, stall_id, device_id, cashier_id, business_date, opened_at)
    values (day_id, stall_id, device_id, cashier_id, (now() at time zone 'Asia/Manila')::date, now() - interval '1 hour');
  insert into public.transactions (id, stall_id, device_id, cashier_id, receipt_number, subtotal, total_amount, occurred_at)
    values (tx_id, stall_id, device_id, cashier_id, tx_id::text, 100, 100, now());
  insert into public.transaction_items (transaction_id, product_id, product_name, quantity, unit_price, line_total)
    values (tx_id, serve_id, 'Vanilla cone', 1, 100, 100);

  perform set_config('request.headers', jsonb_build_object('x-session-token', token, 'x-device-id', 'recipe-test-device')::text, true);
  if public.post_sale_inventory_ledger(tx_id, 'recipe integration test') <> 2
    or public.get_stock_on_hand(stall_id, powder_id) <> 920
    or public.get_stock_on_hand(stall_id, cone_id) <> 19 then
    raise exception 'Recipe backflush did not consume the exact ingredients';
  end if;

  response := public.push_daily_store_closing(jsonb_build_object(
    'id', close_id, 'stall_id', stall_id, 'business_day_id', day_id,
    'business_date', (now() at time zone 'Asia/Manila')::date,
    'gross_sales', 0, 'cogs', 0, 'waste_cost', 0, 'overhead_cost', 0, 'net_profit', 0,
    'expected_cash', 85, 'collected_cash', 85, 'device_id', device_id, 'closed_at', now(),
    'revenue_deduction', 15, 'deduction_reason', 'Customer refund'
  ));
  if response ->> 'status' <> 'accepted' or not exists (
    select 1 from public.daily_store_closings where id = close_id and gross_sales = 100 and cogs = 42
      and overhead_cost = 10 and revenue_deduction = 15 and deduction_reason = 'Customer refund'
      and net_profit = 33 and expected_cash = 85 and collected_cash = 85
  ) then raise exception 'Daily closing did not use authoritative profit totals'; end if;

  begin
    perform public.push_daily_store_closing(jsonb_build_object(
      'id', gen_random_uuid(), 'stall_id', stall_id, 'business_day_id', day_id,
      'business_date', (now() at time zone 'Asia/Manila')::date,
      'collected_cash', 100, 'device_id', device_id, 'closed_at', now(),
      'revenue_deduction', 1, 'deduction_reason', ''
    ));
    raise exception 'A reasonless revenue deduction was accepted';
  exception when sqlstate '22023' then
    null;
  end;
end;
$$;

rollback;
