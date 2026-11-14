-- Disposable database only. Verify that a POS close and its physical counts commit together.
begin;
set local search_path = public, private, extensions;
do $$
declare
  stall uuid := gen_random_uuid();
  cashier uuid := gen_random_uuid();
  admin_id uuid := gen_random_uuid();
  device uuid := gen_random_uuid();
  day_id uuid := gen_random_uuid();
  closing_id uuid := gen_random_uuid();
  cup uuid := gen_random_uuid();
  cone uuid := gen_random_uuid();
  powder uuid := gen_random_uuid();
  coke uuid := gen_random_uuid();
  sprite uuid;
  menu uuid := gen_random_uuid();
  closed_time timestamptz := date_trunc('day', now() at time zone 'Asia/Manila') at time zone 'Asia/Manila';
  business_date_value date := (closed_time at time zone 'Asia/Manila')::date;
  cashier_token text := encode(gen_random_bytes(32), 'hex');
  admin_token text := encode(gen_random_bytes(32), 'hex');
  cashier_headers text := jsonb_build_object('x-session-token', cashier_token, 'x-device-id', 'count-hardware');
  admin_headers text := jsonb_build_object('x-session-token', admin_token);
  catalog jsonb;
  counts jsonb;
  payload jsonb;
  response jsonb;
  snapshot jsonb;
begin
  insert into public.stalls(id, name, code) values(stall, 'Closing count fixture', 'COUNT-' || left(stall::text, 8));
  insert into public.app_users(id, stall_id, email, employee_id, display_name, role, password_hash)
    values (cashier, stall, null, 'COUNTCASHIER', 'Cashier', 'cashier', 'unused'),
      (admin_id, stall, admin_id::text || '@example.invalid', null, 'Admin', 'system_admin', 'unused');
  insert into public.app_sessions(user_id, token_hash, expires_at) values
    (cashier, encode(digest(cashier_token, 'sha256'), 'hex'), now() + interval '1 hour'),
    (admin_id, encode(digest(admin_token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.devices(id, stall_id, device_name, activation_code_hash, hardware_id)
    values (device, stall, 'Closing count POS', 'unused', 'count-hardware');
  insert into public.business_days(id, stall_id, device_id, cashier_id, business_date, opened_at, closed_at, closing_cash_total)
    values (day_id, stall, device, cashier, business_date_value, closed_time - interval '2 hours', closed_time, 0);

  insert into public.products(id, stall_id, sku, name, unit, base_unit, product_type, is_sellable, count_at_closing) values
    (cup, stall, 'CUP-COUNT', 'Cups', 'piece', 'piece', 'packaging', false, true),
    (cone, stall, 'CONE-COUNT', 'Cones', 'piece', 'piece', 'packaging', false, true),
    (coke, stall, 'COKE-COUNT', 'Coke', 'piece', 'piece', 'raw', false, true),
    (menu, stall, 'CUP-SERVING', 'Vanilla cup serving', 'piece', 'piece', 'sellable', true, false);
  insert into public.products(id, stall_id, sku, name, unit, base_unit, product_type, is_sellable,
    pack_size, conversion_rate, purchase_unit_name, tracks_powder_packs, count_at_closing)
    values (powder, stall, 'UBE-COUNT', 'Ube powder', 'g', 'g', 'raw', false, 1000, 1, 'Pack', true, true);
  perform set_config('request.headers', admin_headers, true);
  response := public.save_product_with_recipe(jsonb_build_object(
    'stall_id', stall, 'name', 'Sprite', 'unit', 'piece', 'base_unit', 'piece', 'product_type', 'raw',
    'is_sellable', false, 'purchase_unit_name', 'Case', 'purchase_quantity', 24, 'pack_size', 1,
    'conversion_rate', 24, 'cost_price', 240, 'sale_price', 0, 'low_stock_threshold', 0, 'count_at_closing', true
  ));
  sprite := (response->>'id')::uuid;
  if response->>'count_at_closing' is distinct from 'true' then
    raise exception 'The IMS did not persist the closing-count setting';
  end if;

  insert into public.inventory_ledger(stall_id, product_id, quantity_delta, movement_type, occurred_at) values
    (stall, cup, 100, 'receive', closed_time - interval '3 hours'),
    (stall, cone, 30, 'receive', closed_time - interval '3 hours'),
    (stall, powder, 3000, 'receive', closed_time - interval '3 hours'),
    (stall, coke, 10, 'receive', closed_time - interval '3 hours'),
    (stall, sprite, 20, 'receive', closed_time - interval '3 hours'),
    (stall, cup, -1, 'sale', closed_time - interval '30 minutes'),
    (stall, cup, 5, 'receive', closed_time + interval '5 minutes');

  perform set_config('request.headers', cashier_headers, true);
  catalog := public.get_pos_catalog_revision();
  if catalog->>'closing_stock_counts_supported' is distinct from 'true' or
    (select count(*) from jsonb_array_elements(catalog->'products') p where p->>'count_at_closing' = 'true') <> 5 then
    raise exception 'The POS catalog did not expose the exact physical count list';
  end if;
  -- A later catalog edit must not change an already saved offline count list.
  update public.products set count_at_closing = false where id = cone;
  select jsonb_agg(jsonb_build_object('id', gen_random_uuid(), 'product_id', target.product_id,
    'quantity', target.quantity, 'unit', target.unit) order by target.product_id)
    into counts from (values (cup, 97, 'piece'), (cone, 30, 'piece'),
      (powder, 2, 'pack'), (coke, 0, 'piece'), (sprite, 21, 'piece')) target(product_id, quantity, unit);
  payload := jsonb_build_object('id', closing_id, 'stall_id', stall, 'business_day_id', day_id,
    'device_id', device, 'business_date', business_date_value, 'closed_at', closed_time,
    'gross_sales', 0, 'cogs', 0, 'waste_cost', 0, 'expected_cash', 0, 'collected_cash', 0,
    'revenue_deduction', 0, 'sale_count', 0, 'movement_count', 1, 'deduction_count', 0, 'reversal_count', 0,
    'stock_count_revision_id', catalog->>'revision_id', 'stock_counts', counts);

  perform set_config('request.headers', admin_headers, true);
  begin
    perform public.push_daily_store_closing_v2(payload);
    raise exception 'IMS administrator submitted a cashier physical count';
  exception when insufficient_privilege then null;
  end;

  perform set_config('request.headers', cashier_headers, true);
  begin
    perform public.push_daily_store_closing_v2(payload || jsonb_build_object('stock_counts', counts - 0));
    raise exception 'An incomplete count list was accepted';
  exception when sqlstate '22023' then
    if sqlerrm <> 'CLOSING_STOCK_LIST_MISMATCH' then raise; end if;
  end;
  begin
    perform public.push_daily_store_closing_v2(jsonb_set(payload, '{stock_counts,4,quantity}', '1.5'));
    raise exception 'A fractional piece or pack count was accepted';
  exception when sqlstate '22023' then
    if sqlerrm <> 'INVALID_CLOSING_STOCK_COUNT' then raise; end if;
  end;
  if exists (select 1 from public.daily_store_closings where id = closing_id)
    or exists (select 1 from public.inventory_ledger where reference_id = closing_id) then
    raise exception 'A rejected count partially saved the close';
  end if;

  response := public.push_daily_store_closing_v2(payload);
  if response->>'status' is distinct from 'accepted' or (response->>'stock_counts_accepted')::integer <> 5
    or public.get_stock_on_hand(stall, cup) <> 102
    or public.get_stock_on_hand(stall, cone) <> 30
    or public.get_stock_on_hand(stall, powder) <> 2000
    or public.get_stock_on_hand(stall, coke) <> 0
    or public.get_stock_on_hand(stall, sprite) <> 21 then
    raise exception 'The close failed to reconcile counts or preserve the later receipt';
  end if;
  if (select sum(sealed_packs_delta) from public.inventory_ledger where product_id = powder) <> 2
    or not exists (select 1 from public.inventory_ledger l where l.reference_id = closing_id
      and l.product_id = cone and l.quantity_delta = 0 and l.counted_quantity = 30)
    or (select waste_cost from public.daily_store_closings where id = closing_id) <> 0 then
    raise exception 'The saved ledger lost sealed packs, unchanged counts, or the original waste total';
  end if;
  response := public.push_daily_store_closing_v2(payload);
  if response->>'status' is distinct from 'duplicate'
    or (select count(*) from public.inventory_ledger where reference_id = closing_id) <> 5 then
    raise exception 'Retry reapplied a saved stock count';
  end if;
  begin
    perform public.push_daily_store_closing_v2(payload - 'stock_counts' - 'stock_count_revision_id');
    raise exception 'A replay dropped the saved count data';
  exception when sqlstate '22023' then
    if sqlerrm <> 'INVALID_CLOSING_STOCK_COUNTS' then raise; end if;
  end;
  begin
    perform public.push_daily_store_closing_v2(jsonb_set(payload, '{stock_counts,0,quantity}', '50'));
    raise exception 'Changed count replay was accepted';
  exception when unique_violation then
    if sqlerrm <> 'CLOSING_STOCK_COUNT_CONFLICT' then raise; end if;
  end;
  if (select count(*) from public.get_pos_inventory_ledger_page_v2() l
    where l.reference_id = closing_id and l.counted_quantity is not null) <> 5 then
    raise exception 'The POS ledger did not return the acknowledged counts';
  end if;

  perform set_config('request.headers', admin_headers, true);
  snapshot := public.get_ims_financial_snapshot(stall);
  if (select count(*) from jsonb_array_elements(snapshot->'inventory') l
    where l->>'reference_id' = closing_id::text and l->>'counted_quantity' is not null) <> 5 then
    raise exception 'The IMS snapshot lost closing counts';
  end if;
  perform public.reset_closed_operating_day(stall, day_id);
  snapshot := public.get_ims_financial_snapshot(stall);
  if snapshot->'archived_business_dates'->>day_id::text is distinct from business_date_value::text
    or public.get_stock_on_hand(stall, cup) <> 102 then
    raise exception 'Archiving the operating day lost the inventory audit or adjusted stock';
  end if;
  perform public.reset_stall_transactions(stall);
  if (select count(*) from public.inventory_ledger l
    where l.reference_id = closing_id and l.business_day_id is null) <> 5 then
    raise exception 'Resetting sales history lost the stock-count audit';
  end if;
end;
$$;
rollback;
