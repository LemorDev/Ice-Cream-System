-- Make secured inventory and reversal helpers honor explicit Owner stall access.

create or replace function public.get_stock_on_hand(p_stall_id uuid, p_product_id uuid)
returns numeric(12,3)
language sql stable security definer set search_path = public as $$
  select coalesce(sum(il.quantity_delta), 0)::numeric(12,3)
  from public.inventory_ledger il
  where il.stall_id = p_stall_id
    and public.can_access_stall(p_stall_id)
    and il.product_id = p_product_id
    and il.deleted_at is null;
$$;

create or replace function public.get_stock_on_hand(p_product_id uuid)
returns numeric(12,3)
language sql stable security definer set search_path = public as $$
  select coalesce(sum(il.quantity_delta), 0)::numeric(12,3)
  from public.products p
  left join public.inventory_ledger il
    on il.product_id = p.id and il.stall_id = p.stall_id and il.deleted_at is null
  where p.id = p_product_id
    and p.deleted_at is null
    and public.can_access_stall(p.stall_id);
$$;

create or replace function public.get_low_stock_threshold(p_product_id uuid)
returns numeric(12,3)
language sql stable security definer set search_path = public as $$
  select coalesce(p.low_stock_threshold, 0)::numeric(12,3)
  from public.products p
  where p.id = p_product_id
    and p.deleted_at is null
    and public.can_access_stall(p.stall_id);
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
  if public.current_app_user_id() is null then
    raise exception 'AUTH_REQUIRED' using errcode = '28000';
  end if;
  select * into sale_tx
  from public.transactions t
  where t.id = p_transaction_id
    and (
      public.can_manage_stall(t.stall_id)
      or (public.current_app_role() = 'cashier' and t.stall_id = public.current_app_stall_id()
        and t.cashier_id = public.current_app_user_id())
    )
    and t.deleted_at is null
  for update;
  if not found then raise exception 'Transaction not found' using errcode = 'P0002'; end if;
  if sale_tx.status <> 'completed' then raise exception 'Only completed sales can be posted'; end if;
  if exists (
    select 1 from public.inventory_ledger il
    where il.reference_id = sale_tx.id and il.movement_type = 'sale' and il.deleted_at is null
  ) then return 0; end if;

  insert into public.inventory_ledger (
    stall_id, product_id, quantity_delta, movement_type, reason, reference_id, occurred_at
  )
  select sale_tx.stall_id, ti.product_id, -ti.quantity, 'sale',
    coalesce(nullif(trim(p_reason), ''), 'sale'), sale_tx.id, sale_tx.occurred_at
  from public.transaction_items ti
  join public.products p on p.id = ti.product_id and p.stall_id = sale_tx.stall_id
  where ti.transaction_id = sale_tx.id and ti.deleted_at is null and ti.product_id is not null;
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
  if public.current_app_user_id() is null then
    raise exception 'AUTH_REQUIRED' using errcode = '28000';
  end if;
  select * into sale_tx
  from public.transactions t
  where t.id = p_transaction_id
    and (
      public.can_manage_stall(t.stall_id)
      or (public.current_app_role() = 'cashier' and t.stall_id = public.current_app_stall_id()
        and t.cashier_id = public.current_app_user_id())
    )
    and t.deleted_at is null
  for update;
  if not found then raise exception 'Transaction not found' using errcode = 'P0002'; end if;
  if exists (
    select 1 from public.inventory_ledger il
    where il.reference_id = sale_tx.id and il.movement_type in ('void_restock', 'void_waste') and il.deleted_at is null
  ) then return 0; end if;
  if nullif(trim(p_reason), '') is null then raise exception 'A reversal reason is required' using errcode = '22023'; end if;
  if sale_tx.status <> 'completed' then raise exception 'Only completed sales can be reversed' using errcode = '22023'; end if;
  if not exists (
    select 1 from public.inventory_ledger il
    where il.reference_id = sale_tx.id and il.stall_id = sale_tx.stall_id
      and il.movement_type = 'sale' and il.deleted_at is null
  ) then raise exception 'Sale inventory has not been posted' using errcode = '22023'; end if;

  update public.transactions set status = 'voided' where id = sale_tx.id;
  insert into public.inventory_ledger (
    stall_id, product_id, quantity_delta, movement_type, reason, reference_id, occurred_at
  )
  select sale_tx.stall_id, il.product_id,
    case when p_restock then -il.quantity_delta else 0 end,
    case when p_restock then 'void_restock' else 'void_waste' end::public.inventory_movement_type,
    trim(p_reason), sale_tx.id, now()
  from public.inventory_ledger il
  where il.reference_id = sale_tx.id and il.stall_id = sale_tx.stall_id
    and il.movement_type = 'sale' and il.deleted_at is null;
  get diagnostics inserted_rows = row_count;
  return inserted_rows;
end;
$$;
