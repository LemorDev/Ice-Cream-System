-- Run against a disposable database after migrations. All fixtures roll back.
begin;
set local search_path = public, extensions;

do $$
declare
  test_stall uuid := gen_random_uuid();
  test_raw uuid := gen_random_uuid();
  test_cup uuid := gen_random_uuid();
  test_menu uuid := gen_random_uuid();
begin
  insert into public.stalls (id, name, code)
  values (test_stall, 'Stockable fixture', 'STOCK-' || substr(test_stall::text, 1, 8));
  insert into public.products (id, stall_id, sku, name, unit, is_sellable, product_type, base_unit)
  values
    (test_raw, test_stall, 'TEST-RAW', 'Test powder', 'g', false, 'raw', 'g'),
    (test_cup, test_stall, 'TEST-CUP', 'Test sundae cup', 'piece', false, 'packaging', 'piece'),
    (test_menu, test_stall, 'TEST-MENU', 'Test sundae', 'piece', true, 'sellable', 'piece');

  insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type)
  values (test_stall, test_raw, 100, 'receive'),
         (test_stall, test_cup, 10, 'receive');

  begin
    insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type)
    values (test_stall, test_menu, 1, 'receive');
    raise exception 'Sellable item was accepted as received stock';
  exception when sqlstate '22023' then
    null;
  end;

  begin
    insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type)
    values (test_stall, test_menu, 1, 'adjustment');
    raise exception 'Sellable item was accepted as adjusted stock';
  exception when sqlstate '22023' then
    null;
  end;
end;
$$;

rollback;
