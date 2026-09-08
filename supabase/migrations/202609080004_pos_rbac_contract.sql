-- Cost-free POS catalog and an atomic, device-bound sale endpoint.

create or replace function public.get_pos_products(p_updated_after timestamptz default null)
returns table (
  id uuid, stall_id uuid, category_id uuid, category_name text, sku text, name text, unit text,
  sale_price numeric(12,2), low_stock_threshold numeric(12,3), pack_size numeric(12,3),
  conversion_rate numeric(12,3), is_sellable boolean, updated_at timestamptz, deleted_at timestamptz
)
language plpgsql stable security definer set search_path = public as $$
begin
  if public.current_app_role() <> 'cashier' then raise exception 'CASHIER_REQUIRED' using errcode = '42501'; end if;
  if not exists (
    select 1 from public.devices d
    where d.stall_id = public.current_app_stall_id() and d.is_active and d.deleted_at is null
      and d.hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '')
  ) then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501'; end if;
  return query select p.id, p.stall_id, p.category_id, coalesce(c.name, 'Uncategorized'), p.sku, p.name, p.unit,
    p.sale_price, p.low_stock_threshold, p.pack_size, p.conversion_rate,
    p.is_sellable, p.updated_at, p.deleted_at
  from public.products p
  left join public.product_categories c on c.id = p.category_id and c.stall_id = p.stall_id and c.deleted_at is null
  where p.stall_id = public.current_app_stall_id()
    and (p_updated_after is null or p.updated_at > p_updated_after)
  order by p.updated_at, p.id;
end;
$$;

create or replace function public.push_pos_transaction(p_transaction jsonb)
returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare
  tx_id uuid := nullif(p_transaction ->> 'id', '')::uuid;
  current_stall_id uuid := public.current_app_stall_id();
  device_uuid uuid := nullif(p_transaction ->> 'device_id', '')::uuid;
  existing_tx public.transactions%rowtype;
  item_product_id uuid;
  item_quantity numeric(12,3);
  inserted_items integer := 0;
  total_value numeric(12,2) := nullif(p_transaction ->> 'total_amount', '')::numeric(12,2);
  occurred_at_value timestamptz := coalesce(nullif(p_transaction ->> 'occurred_at', ''), now()::text)::timestamptz;
begin
  if public.current_app_role() <> 'cashier' then raise exception 'CASHIER_REQUIRED' using errcode = '42501'; end if;
  if tx_id is null or current_stall_id is null or device_uuid is null then
    raise exception 'INVALID_TRANSACTION_IDENTITY' using errcode = '22023';
  end if;
  if nullif(p_transaction ->> 'stall_id', '')::uuid <> current_stall_id then
    raise exception 'STALL_MISMATCH' using errcode = '42501';
  end if;
  if coalesce(nullif(p_transaction ->> 'status', ''), 'completed') <> 'completed' then
    raise exception 'ONLY_COMPLETED_SALES_ACCEPTED' using errcode = '22023';
  end if;
  if not exists (
    select 1 from public.devices d where d.id = device_uuid and d.stall_id = current_stall_id
      and d.is_active and d.deleted_at is null
      and d.hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '')
  ) then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501'; end if;
  if not exists (
    select 1 from public.business_days day
    where day.stall_id = current_stall_id and day.device_id = device_uuid and day.deleted_at is null
      and occurred_at_value >= day.opened_at and occurred_at_value <= coalesce(day.closed_at, 'infinity'::timestamptz)
  ) then raise exception 'OPERATING_DAY_REQUIRED' using errcode = '22023'; end if;

  select * into existing_tx from public.transactions t
  where t.id = tx_id or (t.stall_id = current_stall_id and t.receipt_number = p_transaction ->> 'receipt_number')
  order by (t.id = tx_id) desc limit 1;
  if found then
    if existing_tx.stall_id <> current_stall_id or existing_tx.total_amount <> total_value then
      raise exception 'IDEMPOTENCY_CONFLICT' using errcode = '23505';
    end if;
    return jsonb_build_object('status', 'duplicate', 'transaction_id', existing_tx.id,
      'receipt_number', existing_tx.receipt_number, 'inserted_items', 0, 'inserted_ledger', 0);
  end if;

  if jsonb_typeof(p_transaction -> 'items') <> 'array' or jsonb_array_length(p_transaction -> 'items') = 0 then
    raise exception 'TRANSACTION_ITEMS_REQUIRED' using errcode = '22023';
  end if;
  if exists (
    select 1 from jsonb_array_elements(p_transaction -> 'items') item
    where nullif(item ->> 'product_id', '') is null
      or coalesce(nullif(item ->> 'quantity', '')::numeric, 0) <= 0
      or coalesce(nullif(item ->> 'unit_price', '')::numeric, -1) < 0
      or coalesce(nullif(item ->> 'line_total', '')::numeric, -1) < 0
  ) then raise exception 'INVALID_TRANSACTION_ITEM' using errcode = '22023'; end if;
  if total_value is null
    or coalesce(nullif(p_transaction ->> 'subtotal', '')::numeric, -1) < 0
    or coalesce(nullif(p_transaction ->> 'cash_received', '')::numeric, -1) < total_value
    or coalesce(nullif(p_transaction ->> 'change_amount', '')::numeric, -1)
      <> nullif(p_transaction ->> 'cash_received', '')::numeric - total_value
    or total_value <> nullif(p_transaction ->> 'subtotal', '')::numeric
    or total_value <> (select sum((item ->> 'line_total')::numeric) from jsonb_array_elements(p_transaction -> 'items') item)
    or exists (
      select 1 from jsonb_array_elements(p_transaction -> 'items') item
      where (item ->> 'line_total')::numeric <> round((item ->> 'quantity')::numeric * (item ->> 'unit_price')::numeric, 2)
    )
  then raise exception 'TRANSACTION_TOTAL_MISMATCH' using errcode = '22023'; end if;

  -- Product row locks serialize sales that consume any of the same products.
  perform 1 from public.products p
  join (
    select (item ->> 'product_id')::uuid product_id
    from jsonb_array_elements(p_transaction -> 'items') item group by 1
  ) requested on requested.product_id = p.id
  order by p.id for update of p;

  for item_product_id, item_quantity in
    select (item ->> 'product_id')::uuid, sum((item ->> 'quantity')::numeric(12,3))
    from jsonb_array_elements(p_transaction -> 'items') item group by 1 order by 1
  loop
    if not exists (select 1 from public.products p where p.id = item_product_id and p.stall_id = current_stall_id
      and p.is_sellable and p.deleted_at is null) then
      raise exception 'INVALID_PRODUCT' using errcode = '22023';
    end if;
    if public.get_stock_on_hand(current_stall_id, item_product_id) < item_quantity then
      raise exception 'INSUFFICIENT_STOCK' using errcode = 'P0001';
    end if;
  end loop;

  insert into public.transactions (
    id, stall_id, device_id, cashier_id, receipt_number, status,
    subtotal, total_amount, cash_received, change_amount, occurred_at
  ) values (
    tx_id, current_stall_id, device_uuid, public.current_app_user_id(), p_transaction ->> 'receipt_number', 'completed',
    (p_transaction ->> 'subtotal')::numeric(12,2), total_value,
    nullif(p_transaction ->> 'cash_received', '')::numeric(12,2),
    nullif(p_transaction ->> 'change_amount', '')::numeric(12,2),
    occurred_at_value
  );
  insert into public.transaction_items (id, transaction_id, product_id, product_name, quantity, unit_price, line_total)
  select coalesce(nullif(item ->> 'id', '')::uuid, gen_random_uuid()), tx_id,
    (item ->> 'product_id')::uuid, item ->> 'product_name', (item ->> 'quantity')::numeric(12,3),
    (item ->> 'unit_price')::numeric(12,2), (item ->> 'line_total')::numeric(12,2)
  from jsonb_array_elements(p_transaction -> 'items') item;
  get diagnostics inserted_items = row_count;
  perform public.post_sale_inventory_ledger(tx_id, 'android offline sale');
  return jsonb_build_object('status', 'accepted', 'transaction_id', tx_id,
    'receipt_number', p_transaction ->> 'receipt_number', 'inserted_items', inserted_items, 'inserted_ledger', inserted_items);
exception when unique_violation then
  select * into existing_tx from public.transactions t
  where t.stall_id = current_stall_id
    and (t.id = tx_id or t.receipt_number = p_transaction ->> 'receipt_number')
  order by (t.id = tx_id) desc limit 1;
  if found and existing_tx.total_amount = total_value then
    return jsonb_build_object('status', 'duplicate', 'transaction_id', existing_tx.id,
      'receipt_number', existing_tx.receipt_number, 'inserted_items', 0, 'inserted_ledger', 0);
  end if;
  raise exception 'IDEMPOTENCY_CONFLICT' using errcode = '23505';
end;
$$;

grant execute on function public.get_pos_products(timestamptz), public.push_pos_transaction(jsonb) to anon, authenticated;
