-- Keep recipe flavor/category_id independent from the menu category shown in POS Sell.
alter table public.products
  add column if not exists sell_category text;

alter table public.products
  add constraint products_sell_category_length_check
  check (sell_category is null or length(btrim(sell_category)) between 1 and 50);

create or replace function public.save_product_with_recipe(p_product jsonb, p_recipe jsonb default '[]'::jsonb)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare
  saved public.products%rowtype;
  requested_id uuid := nullif(p_product ->> 'id', '')::uuid;
  requested_stall uuid := (p_product ->> 'stall_id')::uuid;
  requested_sell_category text := nullif(btrim(p_product ->> 'sell_category'), '');
begin
  if not public.can_manage_stall(requested_stall) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if coalesce(p_product ->> 'product_type', '') not in ('raw', 'packaging', 'sellable')
    or coalesce(p_product ->> 'base_unit', '') not in ('g', 'ml', 'piece') then
    raise exception 'INVALID_PRODUCT_CLASSIFICATION' using errcode = '22023';
  end if;
  if requested_sell_category is not null and length(requested_sell_category) > 50 then
    raise exception 'INVALID_SELL_CATEGORY' using errcode = '22023';
  end if;

  if requested_id is null then
    insert into public.products (stall_id, category_id, sell_category, name, unit, sale_price, cost_price,
      low_stock_threshold, pack_size, conversion_rate, is_sellable, product_type, base_unit)
    values (requested_stall, nullif(p_product ->> 'category_id', '')::uuid, requested_sell_category,
      btrim(p_product ->> 'name'), p_product ->> 'unit',
      (p_product ->> 'sale_price')::numeric, (p_product ->> 'cost_price')::numeric,
      (p_product ->> 'low_stock_threshold')::numeric, (p_product ->> 'pack_size')::numeric,
      (p_product ->> 'conversion_rate')::numeric, (p_product ->> 'is_sellable')::boolean,
      p_product ->> 'product_type', p_product ->> 'base_unit') returning * into saved;
  else
    update public.products set category_id = nullif(p_product ->> 'category_id', '')::uuid,
      sell_category = case when p_product ? 'sell_category' then requested_sell_category else sell_category end,
      name = btrim(p_product ->> 'name'), unit = p_product ->> 'unit',
      sale_price = (p_product ->> 'sale_price')::numeric, cost_price = (p_product ->> 'cost_price')::numeric,
      low_stock_threshold = (p_product ->> 'low_stock_threshold')::numeric,
      pack_size = (p_product ->> 'pack_size')::numeric, conversion_rate = (p_product ->> 'conversion_rate')::numeric,
      is_sellable = (p_product ->> 'is_sellable')::boolean,
      product_type = p_product ->> 'product_type', base_unit = p_product ->> 'base_unit'
    where id = requested_id and stall_id = requested_stall and deleted_at is null returning * into saved;
    if not found then raise exception 'PRODUCT_NOT_FOUND' using errcode = 'P0002'; end if;
  end if;

  delete from public.product_recipes where parent_product_id = saved.id;
  if saved.product_type = 'sellable' then
    if exists (select 1 from jsonb_array_elements(p_recipe) r where coalesce((r ->> 'quantity')::numeric, 0) <= 0)
      then raise exception 'INVALID_RECIPE_QUANTITY' using errcode = '22023'; end if;
    insert into public.product_recipes (stall_id, parent_product_id, ingredient_product_id, quantity)
    select saved.stall_id, saved.id, (r ->> 'ingredient_product_id')::uuid, (r ->> 'quantity')::numeric
    from jsonb_array_elements(p_recipe) r
    join public.products ingredient on ingredient.id = (r ->> 'ingredient_product_id')::uuid
      and ingredient.stall_id = saved.stall_id and ingredient.product_type in ('raw', 'packaging') and ingredient.deleted_at is null;
    if (select count(*) from jsonb_array_elements(p_recipe)) <> (select count(*) from public.product_recipes where parent_product_id = saved.id)
      then raise exception 'INVALID_RECIPE_INGREDIENT' using errcode = '22023'; end if;
  end if;
  return to_jsonb(saved);
end;
$$;

create or replace function public.get_pos_products(p_updated_after timestamptz default null)
returns table (
  id uuid, stall_id uuid, category_id uuid, category_name text, sku text, name text, unit text,
  sale_price numeric(12,2), low_stock_threshold numeric(12,3), pack_size numeric(12,3),
  conversion_rate numeric(12,3), is_sellable boolean, product_type text, base_unit text,
  updated_at timestamptz, deleted_at timestamptz
)
language plpgsql stable security definer set search_path = public as $$
begin
  if public.current_app_role() <> 'cashier' then raise exception 'CASHIER_REQUIRED' using errcode = '42501'; end if;
  if not exists (select 1 from public.devices d where d.stall_id = public.current_app_stall_id()
    and d.is_active and d.deleted_at is null
    and d.hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', ''))
    then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501'; end if;
  return query select p.id, p.stall_id, p.category_id,
    coalesce(nullif(btrim(p.sell_category), ''), c.name, 'Uncategorized'), p.sku, p.name, p.unit,
    p.sale_price, p.low_stock_threshold, p.pack_size, p.conversion_rate, p.is_sellable,
    p.product_type, p.base_unit, p.updated_at, p.deleted_at
  from public.products p left join public.product_categories c on c.id = p.category_id and c.deleted_at is null
  where p.stall_id = public.current_app_stall_id() and (p_updated_after is null or p.updated_at > p_updated_after)
  order by p.updated_at, p.id;
end;
$$;
