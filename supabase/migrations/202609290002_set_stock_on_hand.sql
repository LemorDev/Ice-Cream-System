-- Set a physical stock count using one auditable ledger adjustment.
-- The RPC calculates the delta on the server; repeated submissions of the
-- same target are harmless. Only System administrators may change stock.

create or replace function public.set_stock_on_hand(
  p_stall_id uuid, p_product_id uuid, p_target_stock numeric, p_reason text
)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  stock_product public.products%rowtype;
  previous_stock numeric(12,3);
  correction numeric(12,3);
begin
  if not public.can_manage_stall(p_stall_id) then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  if p_target_stock is null or p_target_stock < 0 or p_target_stock > 999999999.999
    or p_target_stock <> round(p_target_stock, 3) then
    raise exception 'INVALID_STOCK_COUNT' using errcode = '22023';
  end if;
  if nullif(trim(p_reason), '') is null then
    raise exception 'REASON_REQUIRED' using errcode = '22023';
  end if;

  select * into stock_product from public.products
  where id = p_product_id and stall_id = p_stall_id and deleted_at is null
  for update;
  if not found or stock_product.product_type not in ('raw', 'packaging') then
    raise exception 'STOCKABLE_PRODUCT_REQUIRED' using errcode = '22023';
  end if;

  select coalesce(sum(quantity_delta), 0)::numeric(12,3) into previous_stock
  from public.inventory_ledger
  where stall_id = p_stall_id and product_id = p_product_id and deleted_at is null;
  correction := p_target_stock - previous_stock;
  if correction <> 0 then
    insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type, reason)
    values (p_stall_id, p_product_id, correction, 'adjustment', trim(p_reason));
  end if;
  return jsonb_build_object(
    'status', case when correction = 0 then 'unchanged' else 'adjusted' end,
    'previous_stock', previous_stock,
    'target_stock', p_target_stock,
    'quantity_delta', correction
  );
end;
$$;

revoke all on function public.set_stock_on_hand(uuid, uuid, numeric, text) from public;
grant execute on function public.set_stock_on_hand(uuid, uuid, numeric, text) to anon, authenticated;
