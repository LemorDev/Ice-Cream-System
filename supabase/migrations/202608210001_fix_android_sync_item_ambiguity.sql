-- Fix the Android sale RPC's ambiguous PL/pgSQL variable/column reference.
-- The transaction UUID remains the idempotency key, so retries are safe.

create or replace function public.push_pos_transaction(p_transaction jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  tx_id uuid;
  current_stall_id uuid;
  existing_tx public.transactions%rowtype;
  payload_item jsonb;
  item_product_id uuid;
  item_quantity numeric(12,3);
  inserted_items integer := 0;
  total_amount numeric(12,2);
begin
  if public.current_app_user_id() is null then
    raise exception 'AUTH_REQUIRED' using errcode = '28000';
  end if;

  tx_id := (p_transaction ->> 'id')::uuid;
  current_stall_id := public.current_app_stall_id();
  total_amount := (p_transaction ->> 'total_amount')::numeric(12,2);

  if tx_id is null or current_stall_id is null then
    raise exception 'INVALID_TRANSACTION_IDENTITY' using errcode = '22023';
  end if;

  select * into existing_tx
  from public.transactions t
  where t.id = tx_id
     or (t.stall_id = current_stall_id and t.receipt_number = p_transaction ->> 'receipt_number')
  order by (t.id = tx_id) desc
  limit 1;

  if found then
    if existing_tx.total_amount <> total_amount
       or existing_tx.stall_id <> current_stall_id then
      raise exception 'IDEMPOTENCY_CONFLICT' using errcode = '23505';
    end if;

    return jsonb_build_object(
      'status', 'duplicate',
      'transaction_id', existing_tx.id,
      'receipt_number', existing_tx.receipt_number,
      'inserted_items', 0,
      'inserted_ledger', 0
    );
  end if;

  if jsonb_typeof(p_transaction -> 'items') <> 'array'
     or jsonb_array_length(p_transaction -> 'items') = 0 then
    raise exception 'TRANSACTION_ITEMS_REQUIRED' using errcode = '22023';
  end if;

  for payload_item in
    select item_value
    from jsonb_array_elements(p_transaction -> 'items') as item_values(item_value)
  loop
    item_product_id := nullif(payload_item ->> 'product_id', '')::uuid;
    item_quantity := (payload_item ->> 'quantity')::numeric(12,3);
    if item_product_id is null or item_quantity is null or item_quantity <= 0 then
      raise exception 'INVALID_TRANSACTION_ITEM' using errcode = '22023';
    end if;
    if public.get_stock_on_hand(current_stall_id, item_product_id) < item_quantity then
      raise exception 'INSUFFICIENT_STOCK' using errcode = 'P0001';
    end if;
  end loop;

  insert into public.transactions (
    id, stall_id, device_id, cashier_id, receipt_number, status,
    subtotal, total_amount, cash_received, change_amount, occurred_at
  ) values (
    tx_id,
    current_stall_id,
    nullif(p_transaction ->> 'device_id', '')::uuid,
    public.current_app_user_id(),
    p_transaction ->> 'receipt_number',
    coalesce(nullif(p_transaction ->> 'status', ''), 'completed')::public.transaction_status,
    (p_transaction ->> 'subtotal')::numeric(12,2),
    total_amount,
    nullif(p_transaction ->> 'cash_received', '')::numeric(12,2),
    nullif(p_transaction ->> 'change_amount', '')::numeric(12,2),
    coalesce(nullif(p_transaction ->> 'occurred_at', ''), now()::text)::timestamptz
  );

  insert into public.transaction_items (
    id, transaction_id, product_id, product_name, quantity, unit_price, line_total
  )
  select
    coalesce(nullif(item_value ->> 'id', '')::uuid, gen_random_uuid()),
    tx_id,
    nullif(item_value ->> 'product_id', '')::uuid,
    item_value ->> 'product_name',
    (item_value ->> 'quantity')::numeric(12,3),
    (item_value ->> 'unit_price')::numeric(12,2),
    (item_value ->> 'line_total')::numeric(12,2)
  from jsonb_array_elements(p_transaction -> 'items') as item_values(item_value);

  get diagnostics inserted_items = row_count;
  perform public.post_sale_inventory_ledger(tx_id, 'android offline sale');

  return jsonb_build_object(
    'status', 'accepted',
    'transaction_id', tx_id,
    'receipt_number', p_transaction ->> 'receipt_number',
    'inserted_items', inserted_items,
    'inserted_ledger', inserted_items
  );
exception
  when unique_violation then
    select * into existing_tx
    from public.transactions t
    where t.id = tx_id
       or (t.stall_id = current_stall_id and t.receipt_number = p_transaction ->> 'receipt_number')
    order by (t.id = tx_id) desc
    limit 1;
    if found and existing_tx.total_amount = total_amount then
      return jsonb_build_object(
        'status', 'duplicate',
        'transaction_id', existing_tx.id,
        'receipt_number', existing_tx.receipt_number,
        'inserted_items', 0,
        'inserted_ledger', 0
      );
    end if;
    raise;
end;
$$;

grant execute on function public.push_pos_transaction(jsonb) to anon, authenticated;
