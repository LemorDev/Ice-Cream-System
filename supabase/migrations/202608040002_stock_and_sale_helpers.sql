-- Ice Cream POS: stock calculation helpers and sale posting / reversal helpers.
-- Run this migration after 202608040001_initial_schema.sql has already been applied.

create or replace function public.get_stock_on_hand(p_stall_id uuid, p_product_id uuid)
returns numeric(12,3)
language sql stable security definer set search_path = public as $$
  select coalesce(sum(il.quantity_delta), 0)::numeric(12,3)
  from public.inventory_ledger il
  where il.stall_id = p_stall_id
    and il.product_id = p_product_id
    and il.deleted_at is null;
$$;

create or replace function public.get_stock_on_hand(p_product_id uuid)
returns numeric(12,3)
language sql stable security definer set search_path = public as $$
  select public.get_stock_on_hand(public.current_app_stall_id(), p_product_id);
$$;

create or replace function public.get_low_stock_threshold(p_product_id uuid)
returns numeric(12,3)
language sql stable security definer set search_path = public as $$
  select coalesce(p.low_stock_threshold, 0)::numeric(12,3)
  from public.products p
  where p.id = p_product_id
    and p.deleted_at is null;
$$;

create or replace function public.is_low_stock(p_product_id uuid)
returns boolean
language sql stable security definer set search_path = public as $$
  select public.get_stock_on_hand(p_product_id) <= public.get_low_stock_threshold(p_product_id);
$$;

create or replace function public.post_sale_inventory_ledger(
  p_transaction_id uuid,
  p_reason text default 'sale'
)
returns integer
language plpgsql security definer set search_path = public, extensions as $$
declare
  sale_tx public.transactions%rowtype;
  inserted_rows integer := 0;
begin
  select * into sale_tx
  from public.transactions
  where id = p_transaction_id
    and deleted_at is null;

  if not found then
    raise exception 'Transaction not found' using errcode = 'P0002';
  end if;

  if sale_tx.status <> 'completed' then
    raise exception 'Only completed sales can be posted';
  end if;

  if exists (
    select 1
    from public.inventory_ledger il
    where il.reference_id = sale_tx.id
      and il.movement_type = 'sale'
      and il.deleted_at is null
  ) then
    return 0;
  end if;

  insert into public.inventory_ledger (
    stall_id,
    product_id,
    quantity_delta,
    movement_type,
    reason,
    reference_id,
    occurred_at
  )
  select
    sale_tx.stall_id,
    ti.product_id,
    -ti.quantity,
    'sale',
    coalesce(nullif(trim(p_reason), ''), 'sale'),
    sale_tx.id,
    sale_tx.occurred_at
  from public.transaction_items ti
  where ti.transaction_id = sale_tx.id
    and ti.deleted_at is null
    and ti.product_id is not null;

  get diagnostics inserted_rows = row_count;
  return inserted_rows;
end;
$$;

create or replace function public.reverse_sale_inventory_ledger(
  p_transaction_id uuid,
  p_reason text,
  p_restock boolean default false
)
returns integer
language plpgsql security definer set search_path = public, extensions as $$
declare
  sale_tx public.transactions%rowtype;
  inserted_rows integer := 0;
begin
  select * into sale_tx
  from public.transactions
  where id = p_transaction_id
    and deleted_at is null;

  if not found then
    raise exception 'Transaction not found' using errcode = 'P0002';
  end if;

  if not p_restock then
    update public.transactions
    set status = 'voided'
    where id = sale_tx.id;
    return 0;
  end if;

  if exists (
    select 1
    from public.inventory_ledger il
    where il.reference_id = sale_tx.id
      and il.movement_type = 'void_restock'
      and il.deleted_at is null
  ) then
    return 0;
  end if;

  insert into public.inventory_ledger (
    stall_id,
    product_id,
    quantity_delta,
    movement_type,
    reason,
    reference_id,
    occurred_at
  )
  select
    sale_tx.stall_id,
    ti.product_id,
    ti.quantity,
    'void_restock',
    coalesce(nullif(trim(p_reason), ''), 'sale reversal restock'),
    sale_tx.id,
    now()
  from public.transaction_items ti
  where ti.transaction_id = sale_tx.id
    and ti.deleted_at is null
    and ti.product_id is not null;

  get diagnostics inserted_rows = row_count;
  return inserted_rows;
end;
$$;

grant execute on function public.get_stock_on_hand(uuid, uuid) to anon, authenticated;
grant execute on function public.get_stock_on_hand(uuid) to anon, authenticated;
grant execute on function public.get_low_stock_threshold(uuid) to anon, authenticated;
grant execute on function public.is_low_stock(uuid) to anon, authenticated;
grant execute on function public.post_sale_inventory_ledger(uuid, text) to anon, authenticated;
grant execute on function public.reverse_sale_inventory_ledger(uuid, text, boolean) to anon, authenticated;
