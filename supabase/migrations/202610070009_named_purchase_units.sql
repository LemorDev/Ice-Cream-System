-- Persist purchase-unit labels while keeping inventory quantities in base units.
alter table public.products add column if not exists purchase_unit_name text;

update public.products
set purchase_unit_name = 'Pack'
where product_type in ('raw', 'packaging') and purchase_unit_name is null;

update public.products
set purchase_unit_name = null
where product_type = 'sellable' and purchase_unit_name is not null;

alter table public.products
  add constraint products_purchase_unit_name_check
  check (purchase_unit_name is null or length(btrim(purchase_unit_name)) between 1 and 40);

create or replace function public.save_product_with_recipe(p_product jsonb, p_recipe jsonb default '[]'::jsonb)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare
  saved public.products%rowtype;
  requested_id uuid := nullif(p_product ->> 'id', '')::uuid;
  requested_stall uuid := (p_product ->> 'stall_id')::uuid;
  requested_sell_category text := nullif(btrim(p_product ->> 'sell_category'), '');
  requested_type text := coalesce(p_product ->> 'product_type', '');
  requested_base_unit text := coalesce(p_product ->> 'base_unit', '');
  requested_purchase_quantity numeric;
  requested_purchase_unit text;
  saved_pack_size numeric;
  saved_conversion_rate numeric;
begin
  if not public.can_manage_stall(requested_stall) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if requested_type not in ('raw', 'packaging', 'sellable')
    or requested_base_unit not in ('g', 'ml', 'piece') then
    raise exception 'INVALID_PRODUCT_CLASSIFICATION' using errcode = '22023';
  end if;
  if requested_sell_category is not null and length(requested_sell_category) > 50 then
    raise exception 'INVALID_SELL_CATEGORY' using errcode = '22023';
  end if;

  if requested_type = 'sellable' then
    requested_purchase_quantity := 1;
    requested_purchase_unit := null;
    saved_pack_size := 1;
    saved_conversion_rate := 1;
  else
    requested_purchase_quantity := coalesce(
      nullif(p_product ->> 'purchase_quantity', '')::numeric,
      (p_product ->> 'pack_size')::numeric * (p_product ->> 'conversion_rate')::numeric
    );
    requested_purchase_unit := coalesce(nullif(btrim(p_product ->> 'purchase_unit_name'), ''), 'Pack');
    if requested_purchase_quantity <= 0 then raise exception 'PURCHASE_QUANTITY_REQUIRED' using errcode = '22023'; end if;
    if length(requested_purchase_unit) > 40 then raise exception 'PURCHASE_UNIT_NAME_TOO_LONG' using errcode = '22023'; end if;
    saved_pack_size := case when requested_base_unit = 'piece' then 1 else requested_purchase_quantity end;
    saved_conversion_rate := case when requested_base_unit = 'piece' then requested_purchase_quantity else 1 end;
  end if;

  if requested_id is null then
    insert into public.products (stall_id, category_id, sell_category, purchase_unit_name, name, unit, sale_price, cost_price,
      low_stock_threshold, pack_size, conversion_rate, is_sellable, product_type, base_unit)
    values (requested_stall, nullif(p_product ->> 'category_id', '')::uuid, requested_sell_category,
      requested_purchase_unit, btrim(p_product ->> 'name'), p_product ->> 'unit',
      (p_product ->> 'sale_price')::numeric, (p_product ->> 'cost_price')::numeric,
      (p_product ->> 'low_stock_threshold')::numeric, saved_pack_size, saved_conversion_rate,
      (p_product ->> 'is_sellable')::boolean, requested_type, requested_base_unit) returning * into saved;
  else
    update public.products set category_id = nullif(p_product ->> 'category_id', '')::uuid,
      sell_category = case when p_product ? 'sell_category' then requested_sell_category else sell_category end,
      purchase_unit_name = requested_purchase_unit,
      name = btrim(p_product ->> 'name'), unit = p_product ->> 'unit',
      sale_price = (p_product ->> 'sale_price')::numeric, cost_price = (p_product ->> 'cost_price')::numeric,
      low_stock_threshold = (p_product ->> 'low_stock_threshold')::numeric,
      pack_size = saved_pack_size, conversion_rate = saved_conversion_rate,
      is_sellable = (p_product ->> 'is_sellable')::boolean,
      product_type = requested_type, base_unit = requested_base_unit
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

create or replace function public.get_ims_financial_snapshot(p_stall_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public, private, extensions as $$
declare result jsonb;
begin
  if public.current_app_role() not in ('owner','system_admin') or not public.can_access_stall(p_stall_id) then
    raise exception 'FORBIDDEN' using errcode='42501';
  end if;
  select jsonb_build_object(
    'stall',(select to_jsonb(s) from public.stalls s where s.id=p_stall_id and s.deleted_at is null),
    'categories',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,name,sort_order from public.product_categories where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'products',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,category_id,sell_category,purchase_unit_name,sku,name,unit,sale_price,cost_price,
        low_stock_threshold,pack_size,conversion_rate,is_sellable,product_type,base_unit,updated_at,deleted_at
      from public.products where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'recipes',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,parent_product_id,ingredient_product_id,quantity,updated_at from public.product_recipes where stall_id=p_stall_id) x),'[]'::jsonb),
    'inventory',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,product_id,business_day_id,unit_cost,quantity_delta,movement_type,reason,reference_id,occurred_at
      from public.inventory_ledger where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'transactions',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,business_day_id,cogs,receipt_number,status,subtotal,total_amount,cash_received,change_amount,occurred_at
      from public.transactions where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'business_days',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,device_id,cashier_id,business_date,opened_at,opening_notes,closed_at,closing_cash_total,closing_notes,updated_at
      from public.business_days where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'daily_store_closings',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,business_day_id,business_date,gross_sales,cogs,waste_cost,overhead_cost,revenue_deduction,net_profit,expected_cash,collected_cash,device_id,closed_at
      from public.daily_store_closings where stall_id=p_stall_id) x),'[]'::jsonb),
    'revenue_deductions',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,business_day_id,amount,affects_profit,reason,cashier_id,occurred_at
      from public.revenue_deductions where stall_id=p_stall_id) x),'[]'::jsonb),
    'sale_reversals',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,transaction_id,stall_id,original_day_id,payout_day_id,cashier_id,kind,reason,restock,cash_returned,occurred_at
      from public.sale_reversals where stall_id=p_stall_id) x),'[]'::jsonb),
    'closed_day_corrections',coalesce((select jsonb_agg(to_jsonb(x) order by x.transaction_id) from (
      select transaction_id,stall_id,business_day_id,added_gross,added_cogs,reason,posted_at
      from public.closed_day_corrections where stall_id=p_stall_id) x),'[]'::jsonb),
    'transaction_items',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select ti.id,ti.transaction_id,ti.product_id,ti.product_name,ti.quantity,ti.unit_price,ti.line_total
      from public.transaction_items ti join public.transactions t on t.id=ti.transaction_id
      where t.stall_id=p_stall_id and t.deleted_at is null and ti.deleted_at is null) x),'[]'::jsonb),
    'sale_components',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select sc.ledger_id as id,sc.transaction_id,sc.product_id,sc.quantity,sc.cost_total
      from public.sale_components sc join public.transactions t on t.id=sc.transaction_id
      where t.stall_id=p_stall_id and t.deleted_at is null) x),'[]'::jsonb)
  ) into result;
  return result;
end;
$$;
revoke all on function public.get_ims_financial_snapshot(uuid) from public;
grant execute on function public.get_ims_financial_snapshot(uuid) to anon, authenticated;
