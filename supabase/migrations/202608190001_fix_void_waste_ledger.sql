-- Fix: reverse_sale_inventory_ledger waste path now records void_waste ledger entries.
-- Previously when p_restock = false the function only updated the transaction status
-- but did NOT insert inventory_ledger rows, making waste costs invisible to reports.

create or replace function public.reverse_sale_inventory_ledger(
  p_transaction_id uuid,
  p_reason text,
  p_restock boolean default false
)
returns integer
language plpgsql security definer set search_path = public, extensions as $$
declare
  sale_tx public.transactions%rowtype;
  movement public.inventory_movement_type;
  inserted_rows integer := 0;
begin
  select * into sale_tx
  from public.transactions
  where id = p_transaction_id
    and deleted_at is null;

  if not found then
    raise exception 'Transaction not found' using errcode = 'P0002';
  end if;

  -- Determine the movement type based on the restock flag.
  movement := case when p_restock then 'void_restock' else 'void_waste' end;

  -- Guard against duplicate reversal of the same type.
  if exists (
    select 1
    from public.inventory_ledger il
    where il.reference_id = sale_tx.id
      and il.movement_type = movement
      and il.deleted_at is null
  ) then
    return 0;
  end if;

  -- Always mark the transaction as voided.
  update public.transactions
  set status = 'voided'
  where id = sale_tx.id;

  -- For restock: add stock back (positive delta).
  -- For waste: record the loss as a zero-delta waste marker so COGS reports can
  --            identify which items were wasted.  The original sale deduction
  --            already reduced stock; recording the waste with a zero delta
  --            avoids double-counting the reduction while still giving reports
  --            the void_waste rows they need to compute waste cost.
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
    case when p_restock then ti.quantity else 0 end,
    movement,
    coalesce(nullif(trim(p_reason), ''), 'sale reversal'),
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
