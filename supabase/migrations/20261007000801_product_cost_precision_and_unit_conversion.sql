-- Keep recipe ingredient costs precise while standardizing gram and
-- milliliter pack sizes to the actual base-unit quantity in each pack.

alter table public.products
  alter column cost_price type numeric(10,4) using cost_price::numeric(10,4);

-- Recreate the POS product page with matching four-decimal cost precision.
drop function if exists public.get_pos_products_page(timestamptz, uuid, integer);
create function public.get_pos_products_page(
  p_after_updated_at timestamptz default null,
  p_after_id uuid default null,
  p_limit integer default 250
)
returns table (
  id uuid, stall_id uuid, category_id uuid, category_name text, sku text,
  name text, unit text, sale_price numeric(12,2), cost_price numeric(10,4),
  low_stock_threshold numeric(12,3), pack_size numeric(12,3), conversion_rate numeric(12,3),
  is_sellable boolean, product_type text, base_unit text, updated_at timestamptz, deleted_at timestamptz
)
language plpgsql stable security definer set search_path = public as $$
begin
  if public.current_app_role() <> 'cashier' then raise exception 'CASHIER_REQUIRED' using errcode = '42501'; end if;
  if p_limit < 1 or p_limit > 500 or (p_after_id is not null and p_after_updated_at is null) then
    raise exception 'INVALID_PAGE' using errcode = '22023';
  end if;
  if not exists (select 1 from public.devices d where d.stall_id = public.current_app_stall_id()
    and d.is_active and d.deleted_at is null
    and d.hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '')) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
  end if;
  return query select p.id, p.stall_id, p.category_id,
    coalesce(nullif(btrim(p.sell_category), ''), c.name, 'Uncategorized'), p.sku, p.name, p.unit,
    p.sale_price, p.cost_price, p.low_stock_threshold, p.pack_size, p.conversion_rate,
    p.is_sellable, p.product_type, p.base_unit, p.updated_at, p.deleted_at
  from public.products p
  left join public.product_categories c on c.id = p.category_id and c.stall_id = p.stall_id and c.deleted_at is null
  where p.stall_id = public.current_app_stall_id()
    and (p_after_updated_at is null or p.updated_at > p_after_updated_at
      or (p_after_id is not null and p.updated_at = p_after_updated_at and p.id > p_after_id))
  order by p.updated_at, p.id limit p_limit;
end;
$$;
revoke all on function public.get_pos_products_page(timestamptz, uuid, integer) from public;
grant execute on function public.get_pos_products_page(timestamptz, uuid, integer) to anon, authenticated;

update public.products
set pack_size = pack_size * conversion_rate,
    conversion_rate = 1
where base_unit in ('g', 'ml')
  and conversion_rate <> 1;
