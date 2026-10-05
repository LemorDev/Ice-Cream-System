-- System-administrator-only, audited reset operations for a selected stall.
-- Sales are soft-deleted so transaction records remain recoverable by a database
-- administrator. Stock is reset with balancing ledger entries to preserve its audit trail.

create or replace function public.reset_stall_transactions(p_stall_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  reset_time timestamptz := clock_timestamp();
  transaction_count integer := 0;
begin
  if not public.is_system_admin() then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  if not exists (select 1 from public.stalls where id = p_stall_id and deleted_at is null) then
    raise exception 'Stall not found' using errcode = 'P0002';
  end if;

  select count(*)::integer into transaction_count
  from public.transactions
  where stall_id = p_stall_id and deleted_at is null;

  update public.transaction_items item
  set deleted_at = reset_time
  from public.transactions tx
  where item.transaction_id = tx.id
    and tx.stall_id = p_stall_id
    and tx.deleted_at is null
    and item.deleted_at is null;

  update public.transactions
  set deleted_at = reset_time
  where stall_id = p_stall_id and deleted_at is null;

  update public.daily_closures
  set deleted_at = reset_time
  where stall_id = p_stall_id and deleted_at is null;

  -- These rows summarize the reset sales and should no longer appear as an
  -- authoritative closing. The immutable security audit below records the reset.
  delete from public.daily_store_closings where stall_id = p_stall_id;

  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), p_stall_id, 'stall.transactions_reset', 'stall', p_stall_id::text,
    jsonb_build_object('transaction_count', transaction_count, 'reset_at', reset_time));

  return jsonb_build_object('scope', 'transactions', 'affected_records', transaction_count);
end;
$$;

create or replace function public.reset_stall_stock(p_stall_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  product_count integer := 0;
begin
  if not public.is_system_admin() then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  if not exists (select 1 from public.stalls where id = p_stall_id and deleted_at is null) then
    raise exception 'Stall not found' using errcode = 'P0002';
  end if;

  with balances as (
    select product.id as product_id, coalesce(sum(ledger.quantity_delta), 0)::numeric(12,3) as quantity
    from public.products product
    left join public.inventory_ledger ledger
      on ledger.product_id = product.id
      and ledger.stall_id = p_stall_id
      and ledger.deleted_at is null
    where product.stall_id = p_stall_id and product.deleted_at is null
    group by product.id
  ), inserted as (
    insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type, reason, occurred_at)
    select p_stall_id, product_id, -quantity, 'adjustment', 'System administrator stock reset', clock_timestamp()
    from balances
    where quantity <> 0
    returning 1
  )
  select count(*)::integer into product_count from inserted;

  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), p_stall_id, 'stall.stock_reset', 'stall', p_stall_id::text,
    jsonb_build_object('product_count', product_count, 'reset_at', clock_timestamp()));

  return jsonb_build_object('scope', 'stock', 'affected_records', product_count);
end;
$$;

revoke all on function public.reset_stall_transactions(uuid), public.reset_stall_stock(uuid) from public;
grant execute on function public.reset_stall_transactions(uuid), public.reset_stall_stock(uuid) to anon, authenticated;
