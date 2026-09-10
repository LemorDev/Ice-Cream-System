-- Recipe-based inventory consumption and auditable daily profit closings.

alter table public.products
  add column if not exists product_type text,
  add column if not exists base_unit text;

update public.products set
  product_type = case when is_sellable then 'sellable' else 'raw' end,
  base_unit = case when lower(unit) in ('g', 'gram', 'grams') then 'g'
                   when lower(unit) in ('ml', 'milliliter', 'milliliters') then 'ml'
                   else 'piece' end
where product_type is null or base_unit is null;

alter table public.products
  alter column product_type set default 'sellable',
  alter column product_type set not null,
  alter column base_unit set default 'piece',
  alter column base_unit set not null;
alter table public.products
  add constraint products_product_type_check check (product_type in ('raw', 'packaging', 'sellable')),
  add constraint products_base_unit_check check (base_unit in ('g', 'ml', 'piece'));

create table public.product_recipes (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  parent_product_id uuid not null references public.products(id) on delete cascade,
  ingredient_product_id uuid not null references public.products(id),
  quantity numeric(12,3) not null check (quantity > 0),
  updated_at timestamptz not null default now(),
  unique (parent_product_id, ingredient_product_id),
  check (parent_product_id <> ingredient_product_id)
);

create table public.daily_store_closings (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  business_day_id uuid references public.business_days(id),
  business_date date not null,
  gross_sales numeric(12,2) not null default 0,
  cogs numeric(12,2) not null default 0,
  waste_cost numeric(12,2) not null default 0,
  overhead_cost numeric(12,2) not null default 0,
  net_profit numeric(12,2) not null default 0,
  expected_cash numeric(12,2) not null default 0,
  collected_cash numeric(12,2) not null default 0,
  device_id uuid references public.devices(id),
  closed_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (stall_id, business_date)
);

create trigger product_recipes_set_updated_at before update on public.product_recipes
for each row execute function public.set_updated_at();
create trigger daily_store_closings_set_updated_at before update on public.daily_store_closings
for each row execute function public.set_updated_at();

alter table public.product_recipes enable row level security;
alter table public.daily_store_closings enable row level security;
create policy "administrators manage recipes" on public.product_recipes for all
  using (public.can_manage_stall(stall_id)) with check (public.can_manage_stall(stall_id));
create policy "authorized users view recipes" on public.product_recipes for select
  using (public.can_access_stall(stall_id));
create policy "authorized users view closings" on public.daily_store_closings for select
  using (public.can_access_stall(stall_id));

grant select on public.product_recipes, public.daily_store_closings to anon, authenticated;

create or replace function public.save_product_with_recipe(p_product jsonb, p_recipe jsonb default '[]'::jsonb)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare
  saved public.products%rowtype;
  requested_id uuid := nullif(p_product ->> 'id', '')::uuid;
  requested_stall uuid := (p_product ->> 'stall_id')::uuid;
begin
  if not public.can_manage_stall(requested_stall) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if coalesce(p_product ->> 'product_type', '') not in ('raw', 'packaging', 'sellable')
    or coalesce(p_product ->> 'base_unit', '') not in ('g', 'ml', 'piece') then
    raise exception 'INVALID_PRODUCT_CLASSIFICATION' using errcode = '22023';
  end if;
  if requested_id is null then
    insert into public.products (stall_id, category_id, name, unit, sale_price, cost_price,
      low_stock_threshold, pack_size, conversion_rate, is_sellable, product_type, base_unit)
    values (requested_stall, nullif(p_product ->> 'category_id', '')::uuid, trim(p_product ->> 'name'),
      p_product ->> 'unit', (p_product ->> 'sale_price')::numeric, (p_product ->> 'cost_price')::numeric,
      (p_product ->> 'low_stock_threshold')::numeric, (p_product ->> 'pack_size')::numeric,
      (p_product ->> 'conversion_rate')::numeric, (p_product ->> 'is_sellable')::boolean,
      p_product ->> 'product_type', p_product ->> 'base_unit') returning * into saved;
  else
    update public.products set category_id = nullif(p_product ->> 'category_id', '')::uuid,
      name = trim(p_product ->> 'name'), unit = p_product ->> 'unit',
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

drop function if exists public.get_pos_products(timestamptz);
create function public.get_pos_products(p_updated_after timestamptz default null)
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
  return query select p.id, p.stall_id, p.category_id, coalesce(c.name, 'Uncategorized'), p.sku, p.name, p.unit,
    p.sale_price, p.low_stock_threshold, p.pack_size, p.conversion_rate, p.is_sellable,
    p.product_type, p.base_unit, p.updated_at, p.deleted_at
  from public.products p left join public.product_categories c on c.id = p.category_id and c.deleted_at is null
  where p.stall_id = public.current_app_stall_id() and (p_updated_after is null or p.updated_at > p_updated_after)
  order by p.updated_at, p.id;
end;
$$;

create or replace function public.get_stock_on_hand(p_stall_id uuid, p_product_id uuid)
returns numeric language plpgsql stable security definer set search_path = public as $$
declare recipe_count integer; available numeric;
begin
  if not public.can_access_stall(p_stall_id) then return 0; end if;
  select count(*) into recipe_count from public.product_recipes where parent_product_id = p_product_id and stall_id = p_stall_id;
  if recipe_count > 0 then
    select min(public.get_stock_on_hand(r.ingredient_product_id) / r.quantity) into available
    from public.product_recipes r where r.parent_product_id = p_product_id and r.stall_id = p_stall_id;
    return greatest(coalesce(available, 0), 0);
  end if;
  return coalesce((select sum(quantity_delta) from public.inventory_ledger
    where stall_id = p_stall_id and product_id = p_product_id and deleted_at is null), 0);
end;
$$;

create or replace function public.get_pos_recipes(p_updated_after timestamptz default null)
returns table (id uuid, stall_id uuid, parent_product_id uuid, ingredient_product_id uuid, quantity numeric(12,3), updated_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if public.current_app_role() <> 'cashier' then raise exception 'CASHIER_REQUIRED' using errcode = '42501'; end if;
  if not exists (select 1 from public.devices d where d.stall_id = public.current_app_stall_id()
    and d.is_active and d.deleted_at is null
    and d.hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', ''))
    then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501'; end if;
  return query select r.id, r.stall_id, r.parent_product_id, r.ingredient_product_id, r.quantity, r.updated_at
    from public.product_recipes r where r.stall_id = public.current_app_stall_id()
      and (p_updated_after is null or r.updated_at > p_updated_after) order by r.updated_at, r.id;
end;
$$;

-- Backflush ingredients for recipe products; legacy products continue to consume their own stock.
create or replace function public.post_sale_inventory_ledger(p_transaction_id uuid, p_reason text default 'sale')
returns integer language plpgsql security definer set search_path = public, extensions as $$
declare sale_tx public.transactions%rowtype; inserted_rows integer := 0;
begin
  if public.current_app_user_id() is null then raise exception 'AUTH_REQUIRED' using errcode = '28000'; end if;
  select * into sale_tx from public.transactions where id = p_transaction_id and deleted_at is null for update;
  if not found or not public.can_access_stall(sale_tx.stall_id) then raise exception 'Transaction not found' using errcode = 'P0002'; end if;
  if exists (select 1 from public.inventory_ledger where reference_id = sale_tx.id and movement_type = 'sale' and deleted_at is null) then return 0; end if;
  insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type, reason, reference_id, occurred_at)
  select sale_tx.stall_id, usage.product_id, -sum(usage.quantity), 'sale', coalesce(nullif(trim(p_reason), ''), 'sale'), sale_tx.id, sale_tx.occurred_at
  from (
    select coalesce(r.ingredient_product_id, ti.product_id) product_id,
      ti.quantity * coalesce(r.quantity, 1) quantity
    from public.transaction_items ti
    left join public.product_recipes r on r.parent_product_id = ti.product_id
    where ti.transaction_id = sale_tx.id and ti.deleted_at is null and ti.product_id is not null
  ) usage group by usage.product_id;
  get diagnostics inserted_rows = row_count;
  return inserted_rows;
end;
$$;

create or replace function public.push_daily_store_closing(p_closing jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  saved public.daily_store_closings%rowtype;
  current_stall uuid := public.current_app_stall_id();
  close_date date := (p_closing ->> 'business_date')::date;
  authoritative_gross numeric(12,2);
  authoritative_cogs numeric(12,2);
  authoritative_waste numeric(12,2);
  authoritative_overhead numeric(12,2);
begin
  if public.current_app_role() <> 'cashier' or (p_closing ->> 'stall_id')::uuid <> current_stall then
    raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  select coalesce(sum(t.total_amount), 0) into authoritative_gross from public.transactions t
    where t.stall_id = current_stall and t.status = 'completed'
      and (t.occurred_at at time zone 'Asia/Manila')::date = close_date and t.deleted_at is null;
  select coalesce(sum(ti.quantity * p.cost_price), 0) into authoritative_cogs
    from public.transaction_items ti join public.transactions t on t.id = ti.transaction_id
    join public.products p on p.id = ti.product_id
    where t.stall_id = current_stall and t.status = 'completed'
      and (t.occurred_at at time zone 'Asia/Manila')::date = close_date and ti.deleted_at is null;
  select coalesce(sum(abs(il.quantity_delta) * (p.cost_price / nullif(p.pack_size * p.conversion_rate, 0))), 0)
    into authoritative_waste from public.inventory_ledger il join public.products p on p.id = il.product_id
    where il.stall_id = current_stall and (il.occurred_at at time zone 'Asia/Manila')::date = close_date
      and il.movement_type = 'adjustment' and il.quantity_delta < 0 and il.deleted_at is null;
  select coalesce(sum((item ->> 'dailyRate')::numeric), 0) into authoritative_overhead
    from public.stalls s, jsonb_array_elements(coalesce(s.overhead_config, '[]'::jsonb)) item where s.id = current_stall;

  insert into public.daily_store_closings (id, stall_id, business_day_id, business_date, gross_sales, cogs,
    waste_cost, overhead_cost, net_profit, expected_cash, collected_cash, device_id, closed_at)
  values ((p_closing ->> 'id')::uuid, current_stall, nullif(p_closing ->> 'business_day_id', '')::uuid,
    close_date, authoritative_gross, authoritative_cogs,
    authoritative_waste, authoritative_overhead,
    authoritative_gross - authoritative_cogs - authoritative_waste - authoritative_overhead, authoritative_gross,
    (p_closing ->> 'collected_cash')::numeric, nullif(p_closing ->> 'device_id', '')::uuid,
    coalesce(nullif(p_closing ->> 'closed_at', '')::timestamptz, now()))
  on conflict (stall_id, business_date) do update set collected_cash = excluded.collected_cash,
    expected_cash = excluded.expected_cash, gross_sales = excluded.gross_sales, cogs = excluded.cogs,
    waste_cost = excluded.waste_cost, overhead_cost = excluded.overhead_cost, net_profit = excluded.net_profit,
    closed_at = excluded.closed_at returning * into saved;
  return jsonb_build_object('status', 'accepted', 'closing_id', saved.id);
end;
$$;

create or replace function public.push_pos_inventory_entry(p_entry jsonb)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare current_stall uuid := public.current_app_stall_id(); saved_id uuid;
begin
  if public.current_app_role() <> 'cashier' or (p_entry ->> 'stall_id')::uuid <> current_stall then
    raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if (p_entry ->> 'movement_type') <> 'sale' or (p_entry ->> 'quantity_delta')::numeric >= 0
    or not exists (select 1 from public.transactions t where t.id = nullif(p_entry ->> 'reference_id', '')::uuid
      and t.stall_id = current_stall and t.cashier_id = public.current_app_user_id()) then
    raise exception 'INVALID_LEDGER_ENTRY' using errcode = '22023'; end if;
  select id into saved_id from public.inventory_ledger
  where stall_id = current_stall and product_id = (p_entry ->> 'product_id')::uuid
    and movement_type = p_entry ->> 'movement_type'
    and reference_id is not distinct from nullif(p_entry ->> 'reference_id', '')::uuid
    and deleted_at is null limit 1;
  if saved_id is not null then return jsonb_build_object('status', 'duplicate', 'ledger_id', saved_id); end if;
  insert into public.inventory_ledger (id, stall_id, product_id, quantity_delta, movement_type, reason, reference_id, occurred_at)
  values ((p_entry ->> 'id')::uuid, current_stall, (p_entry ->> 'product_id')::uuid,
    (p_entry ->> 'quantity_delta')::numeric, p_entry ->> 'movement_type', nullif(p_entry ->> 'reason', ''),
    nullif(p_entry ->> 'reference_id', '')::uuid, (p_entry ->> 'occurred_at')::timestamptz)
  on conflict (id) do nothing returning id into saved_id;
  return jsonb_build_object('status', case when saved_id is null then 'duplicate' else 'accepted' end,
    'ledger_id', coalesce(saved_id, (p_entry ->> 'id')::uuid));
end;
$$;

grant execute on function public.save_product_with_recipe(jsonb,jsonb), public.get_pos_recipes(timestamptz),
  public.push_daily_store_closing(jsonb), public.push_pos_inventory_entry(jsonb) to anon, authenticated;
