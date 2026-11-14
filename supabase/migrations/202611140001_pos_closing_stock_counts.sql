-- A closing count is a measured stock balance, including when the variance is zero.
alter type public.inventory_movement_type add value if not exists 'closing_count';

alter table public.products add column count_at_closing boolean not null default false;
alter table public.products add constraint closing_stock_item_required
  check (not count_at_closing or product_type in ('raw', 'packaging'));

update public.products
set count_at_closing = true
where deleted_at is null and product_type in ('raw', 'packaging') and (
  name ~* '\m(cups?|cones?|sprite|coke|coca[ -]?cola)\M'
  or (name ~* '\m(vanilla|vanila|ube)\M' and name ~* '\mpowder\M')
);

alter table public.inventory_ledger
  add column counted_quantity numeric(12,3),
  add column counted_unit text,
  drop constraint inventory_ledger_nonzero_or_waste_check;
alter table public.inventory_ledger
  add constraint inventory_ledger_nonzero_or_waste_check check (
    quantity_delta <> 0 or movement_type::text in ('void_waste', 'pack_unsealed', 'pack_reclassified', 'closing_count')
  ),
  add constraint inventory_ledger_closing_count_check check (
    case when movement_type::text = 'closing_count' then
      reference_id is not null
      and counted_quantity is not null and counted_quantity between 0 and 999999999.999
      and counted_unit is not null and counted_unit in ('piece', 'g', 'ml', 'pack')
      and (counted_unit not in ('piece', 'pack') or counted_quantity = trunc(counted_quantity))
    else counted_quantity is null and counted_unit is null end
  );
create unique index inventory_ledger_closing_count_product
  on public.inventory_ledger(business_day_id, product_id) where counted_quantity is not null;

create or replace function public.guard_powder_pack_ledger() returns trigger
language plpgsql security definer set search_path=public as $$
declare tracked boolean; sealed integer; total numeric; units_per_pack numeric;
begin
  select tracks_powder_packs,pack_size*conversion_rate into tracked,units_per_pack
    from public.products where id=new.product_id and stall_id=new.stall_id for update;
  if not found then raise exception 'INVALID_PRODUCT' using errcode='22023'; end if;
  if new.movement_type::text='receive' and tracked then
    if new.quantity_delta<=0 or new.quantity_delta<>trunc(new.quantity_delta/units_per_pack)*units_per_pack then
      raise exception 'POWDER_RECEIVE_WHOLE_PACKS_REQUIRED' using errcode='22023';
    end if;
    new.sealed_packs_delta := (new.quantity_delta/units_per_pack)::integer;
  elsif new.movement_type::text in ('pack_unsealed','pack_reclassified') then
    if not tracked or new.sealed_packs_delta=0
      or (new.movement_type='pack_reclassified' and new.quantity_delta<>0)
      or (new.movement_type='pack_unsealed' and new.quantity_delta<>0
        and new.quantity_delta<>new.sealed_packs_delta*units_per_pack) then
      raise exception 'INVALID_POWDER_PACK_MOVEMENT' using errcode='22023';
    end if;
    if new.movement_type::text='pack_unsealed' and new.sealed_packs_delta>=0 then
      raise exception 'INVALID_PACK_OPENING' using errcode='22023';
    end if;
    select coalesce(sum(sealed_packs_delta),0),coalesce(sum(quantity_delta),0)
      into sealed,total from public.inventory_ledger
      where stall_id=new.stall_id and product_id=new.product_id and deleted_at is null;
    if sealed+new.sealed_packs_delta<0 then
      raise exception 'INSUFFICIENT_SEALED_PACKS' using errcode='P0001';
    end if;
    if new.movement_type::text='pack_unsealed' and new.quantity_delta<0 and total+new.quantity_delta<0 then
      raise exception 'INSUFFICIENT_STOCK' using errcode='P0001';
    end if;
    if new.movement_type::text='pack_reclassified' and total-(sealed+new.sealed_packs_delta)*units_per_pack<0 then
      raise exception 'INSUFFICIENT_OPERATIONAL_POWDER' using errcode='P0001';
    end if;
  elsif new.sealed_packs_delta<>0 and not (
    new.movement_type::text='closing_count' and tracked and new.counted_unit='pack'
  ) then
    raise exception 'INVALID_SEALED_PACK_DELTA' using errcode='22023';
  end if;
  return new;
end;
$$;

-- Include the count list in the same approved catalog revision the device receives.
create or replace function public.get_pos_catalog_revision()
returns jsonb
language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  device public.devices%rowtype;
  snapshot jsonb;
  revision private.pos_catalog_revisions%rowtype;
  snapshot_hash text;
  expires_at timestamptz := now() + interval '72 hours';
begin
  if public.current_app_role() is distinct from 'cashier'::public.app_role then
    raise exception 'CASHIER_REQUIRED' using errcode = '42501';
  end if;
  select d.* into device from public.devices d
  where d.stall_id = public.current_app_stall_id() and d.is_active and d.deleted_at is null
    and d.hardware_id = nullif(current_setting('request.headers', true), '')::jsonb ->> 'x-device-id';
  if not found then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501'; end if;

  select jsonb_build_object(
    'products', coalesce((select jsonb_agg(to_jsonb(p) order by p.id) from (
      select p.id, p.stall_id, p.category_id, p.sku, p.name, p.unit, p.sale_price, p.cost_price,
        p.low_stock_threshold, p.pack_size, p.conversion_rate, p.tracks_powder_packs, p.is_sellable,
        p.product_type, p.base_unit, p.updated_at, p.deleted_at, p.image_url, p.count_at_closing,
        coalesce(nullif(btrim(p.sell_category), ''), c.name, 'Uncategorized') as category_name
      from public.products p left join public.product_categories c
        on c.id = p.category_id and c.stall_id = p.stall_id and c.deleted_at is null
      where p.stall_id = device.stall_id
    ) p), '[]'::jsonb),
    'recipes', coalesce((select jsonb_agg(to_jsonb(r) order by r.id) from (
      select r.id, r.stall_id, r.parent_product_id, r.ingredient_product_id, r.quantity, r.updated_at
      from public.product_recipes r where r.stall_id = device.stall_id
    ) r), '[]'::jsonb)
  ) into snapshot;

  snapshot_hash := encode(digest(snapshot::text, 'sha256'), 'hex');
  insert into private.pos_catalog_revisions as saved (stall_id, device_id, fingerprint, catalog, valid_until)
  values (device.stall_id, device.id, snapshot_hash, snapshot, expires_at)
  on conflict (device_id, fingerprint) do update set valid_until = excluded.valid_until
    where saved.valid_until < excluded.valid_until - interval '1 hour'
  returning * into revision;
  if not found then
    select * into strict revision from private.pos_catalog_revisions r
    where r.device_id = device.id and r.fingerprint = snapshot_hash;
  end if;

  return snapshot || jsonb_build_object(
    'revision_id', revision.id, 'valid_until', revision.valid_until, 'device_id', device.id, 'add_on_price_cents', 500,
    'closing_stock_counts_supported', true
  );
end;
$$;
revoke all on function public.get_pos_catalog_revision() from public;
grant execute on function public.get_pos_catalog_revision() to anon, authenticated;

create or replace function public.get_ims_financial_snapshot(p_stall_id uuid) returns jsonb
language plpgsql stable security definer set search_path=public,private,extensions as $$
declare result jsonb;
begin
  result := public.get_ims_financial_snapshot_before_powder(p_stall_id);
  result := jsonb_set(result, '{products}', coalesce((
    select jsonb_agg(item || jsonb_build_object('tracks_powder_packs', p.tracks_powder_packs,
      'image_url', p.image_url, 'count_at_closing', p.count_at_closing))
    from jsonb_array_elements(result->'products') item
    join public.products p on p.id = (item->>'id')::uuid
  ), '[]'::jsonb));
  result := jsonb_set(result, '{inventory}', coalesce((
    select jsonb_agg(item || jsonb_build_object('sealed_packs_delta', l.sealed_packs_delta,
      'counted_quantity', l.counted_quantity, 'counted_unit', l.counted_unit))
    from jsonb_array_elements(result->'inventory') item
    join public.inventory_ledger l on l.id = (item->>'id')::uuid
  ), '[]'::jsonb));
  return result || jsonb_build_object('archived_business_dates', coalesce((
    select jsonb_object_agg(d.id, d.business_date) from public.business_days d
    where d.stall_id = p_stall_id and d.deleted_at is not null
  ), '{}'::jsonb));
end;
$$;

alter table public.daily_store_closings add column stock_count_revision_id uuid;

alter function public.push_daily_store_closing_v2(jsonb) rename to push_daily_store_closing_before_counts;
alter function public.push_daily_store_closing_before_counts(jsonb) set schema private;
revoke all on function private.push_daily_store_closing_before_counts(jsonb) from public, anon, authenticated;

create function public.push_daily_store_closing_v2(p_closing jsonb)
returns jsonb language plpgsql security definer set search_path = pg_catalog, public as $$
declare
  day_row public.business_days%rowtype;
  saved public.daily_store_closings%rowtype;
  product public.products%rowtype;
  day_id uuid := (p_closing->>'business_day_id')::uuid;
  closing_id uuid := (p_closing->>'id')::uuid;
  device_id_value uuid := (p_closing->>'device_id')::uuid;
  revision_id uuid := (p_closing->>'stock_count_revision_id')::uuid;
  counts jsonb := p_closing->'stock_counts';
  expected_products jsonb;
  item jsonb;
  approved_product jsonb;
  product_id_value uuid;
  count_quantity numeric;
  count_unit text;
  units_per_pack numeric;
  target_stock numeric;
  previous_stock numeric;
  previous_packs integer;
  result jsonb;
begin
  if not public.active_pos_device(device_id_value)
    or (p_closing->>'stall_id')::uuid is distinct from public.current_app_stall_id() then
    raise exception 'ACTIVE_CASHIER_DEVICE_REQUIRED' using errcode = '42501';
  end if;
  select * into day_row from public.business_days d
  where d.id = day_id and d.device_id = device_id_value and d.stall_id = public.current_app_stall_id()
    and d.deleted_at is null for update;
  if not found or day_row.closed_at is null
    or day_row.closed_at is distinct from (p_closing->>'closed_at')::timestamptz
    or day_row.business_date is distinct from (p_closing->>'business_date')::date then
    raise exception 'OPERATING_DAY_NOT_CLOSED' using errcode = '22023';
  end if;

  select * into saved from public.daily_store_closings c where c.business_day_id = day_id;
  if found and saved.id is distinct from closing_id then
    raise exception 'CLOSING_IDENTITY_CONFLICT' using errcode = '23505';
  end if;
  if revision_id is null and (counts is null or counts = 'null'::jsonb) and saved.stock_count_revision_id is null then
    return private.push_daily_store_closing_before_counts(p_closing);
  end if;
  if closing_id is null or revision_id is null or jsonb_typeof(counts) is distinct from 'array' then
    raise exception 'INVALID_CLOSING_STOCK_COUNTS' using errcode = '22023';
  end if;
  if saved.id is not null and saved.collected_cash is distinct from (p_closing->>'collected_cash')::numeric(12,2) then
    raise exception 'CLOSING_IDENTITY_CONFLICT' using errcode = '23505';
  end if;
  if saved.stock_count_revision_id is not null then
    if saved.stock_count_revision_id is distinct from revision_id or counts is distinct from (
      select coalesce(jsonb_agg(jsonb_build_object('id', l.id, 'product_id', l.product_id,
        'quantity', l.counted_quantity, 'unit', l.counted_unit) order by l.product_id), '[]'::jsonb)
      from public.inventory_ledger l where l.business_day_id = day_id and l.reference_id = closing_id
        and l.movement_type = 'closing_count'
    ) then
      raise exception 'CLOSING_STOCK_COUNT_CONFLICT' using errcode = '23505';
    end if;
    return jsonb_build_object('status', 'duplicate', 'closing_id', closing_id,
      'stock_counts_accepted', jsonb_array_length(counts));
  end if;

  select r.catalog->'products' into expected_products from private.pos_catalog_revisions r
  where r.id = revision_id and r.device_id = device_id_value and r.stall_id = day_row.stall_id;
  if not found or jsonb_array_length(counts) <> (
    select count(*) from jsonb_array_elements(expected_products) p
    where p->>'count_at_closing' = 'true' and p->>'deleted_at' is null
  ) or jsonb_array_length(counts) <> (
    select count(distinct (c->>'product_id')::uuid) from jsonb_array_elements(counts) c
  ) then
    raise exception 'CLOSING_STOCK_LIST_MISMATCH' using errcode = '22023';
  end if;

  -- Verify sales and the drawer before applying any stock corrections. Both commit together.
  result := private.push_daily_store_closing_before_counts(p_closing);
  for item in select c from jsonb_array_elements(counts) c order by (c->>'product_id')::uuid loop
    product_id_value := (item->>'product_id')::uuid;
    count_quantity := (item->>'quantity')::numeric;
    count_unit := item->>'unit';
    select p into approved_product from jsonb_array_elements(expected_products) p
    where (p->>'id')::uuid = product_id_value and p->>'count_at_closing' = 'true'
      and p->>'deleted_at' is null;
    if not found or (item->>'id')::uuid is null
      or count_unit is distinct from (case when approved_product->>'tracks_powder_packs' = 'true'
        then 'pack' else approved_product->>'base_unit' end)
      or count_quantity is null or count_quantity < 0 or count_quantity > 999999999.999
      or count_quantity <> round(count_quantity, 3)
      or (count_unit in ('piece', 'pack') and count_quantity <> trunc(count_quantity)) then
      raise exception 'INVALID_CLOSING_STOCK_COUNT' using errcode = '22023';
    end if;
    select * into product from public.products p
    where p.id = product_id_value and p.stall_id = day_row.stall_id
      and (p.deleted_at is null or p.deleted_at > day_row.closed_at)
      and p.product_type in ('raw', 'packaging') for update;
    units_per_pack := product.pack_size * product.conversion_rate;
    if not found or product.tracks_powder_packs is distinct from (count_unit = 'pack')
      or product.base_unit is distinct from approved_product->>'base_unit'
      or (count_unit = 'pack' and units_per_pack <>
        (approved_product->>'pack_size')::numeric * (approved_product->>'conversion_rate')::numeric) then
      raise exception 'CLOSING_STOCK_UNIT_CHANGED' using errcode = '22023';
    end if;
    target_stock := case when count_unit = 'pack' then count_quantity * units_per_pack else count_quantity end;
    select coalesce(sum(l.quantity_delta), 0), coalesce(sum(l.sealed_packs_delta), 0)
      into previous_stock, previous_packs from public.inventory_ledger l
    where l.stall_id = day_row.stall_id and l.product_id = product_id_value and l.deleted_at is null
      and l.occurred_at <= day_row.closed_at;
    if target_stock > 999999999.999 or abs(target_stock - previous_stock) > 999999999.999 then
      raise exception 'INVALID_CLOSING_STOCK_COUNT' using errcode = '22023';
    end if;

    insert into public.inventory_ledger(id, stall_id, business_day_id, product_id, quantity_delta,
      sealed_packs_delta, movement_type, reason, reference_id, occurred_at, counted_quantity, counted_unit)
    values ((item->>'id')::uuid, day_row.stall_id, day_id, product_id_value, target_stock - previous_stock,
      case when count_unit = 'pack' then count_quantity::integer - previous_packs else 0 end,
      'closing_count', 'Closing stock count', closing_id, day_row.closed_at, count_quantity, count_unit);
  end loop;
  update public.daily_store_closings set stock_count_revision_id = revision_id where id = closing_id;
  return result || jsonb_build_object('stock_counts_accepted', jsonb_array_length(counts));
end;
$$;

revoke all on function public.push_daily_store_closing_v2(jsonb) from public, anon, authenticated;
grant execute on function public.push_daily_store_closing_v2(jsonb) to anon, authenticated;

-- Preserve the existing save behavior and store the count setting in the same transaction.
alter function public.save_product_with_recipe(jsonb, jsonb) rename to save_product_before_closing_counts;
alter function public.save_product_before_closing_counts(jsonb, jsonb) set schema private;
revoke all on function private.save_product_before_closing_counts(jsonb, jsonb) from public, anon, authenticated;

create function public.save_product_with_recipe(p_product jsonb, p_recipe jsonb default '[]'::jsonb)
returns jsonb language plpgsql security definer set search_path = pg_catalog, public as $$
declare
  saved jsonb;
  product_id uuid := (p_product->>'id')::uuid;
  count_setting boolean := (p_product->>'count_at_closing')::boolean;
begin
  -- The product type constraint must also hold while the existing save function updates it.
  if p_product->>'product_type' = 'sellable' and product_id is not null
    and public.can_manage_stall((p_product->>'stall_id')::uuid) then
    update public.products set count_at_closing = false
    where id = product_id and stall_id = (p_product->>'stall_id')::uuid and count_at_closing;
  end if;
  saved := private.save_product_before_closing_counts(p_product, p_recipe);
  if count_setting is not null and saved->>'product_type' in ('raw', 'packaging') then
    update public.products p set count_at_closing = count_setting
    where p.id = (saved->>'id')::uuid
    returning to_jsonb(p) into saved;
  end if;
  return saved;
end;
$$;
revoke all on function public.save_product_with_recipe(jsonb, jsonb) from public, anon, authenticated;
grant execute on function public.save_product_with_recipe(jsonb, jsonb) to anon, authenticated;

-- Return the acknowledged count and server-calculated deltas to the phone.
drop function public.get_pos_inventory_ledger_page_v2(timestamptz,uuid,integer);
create function public.get_pos_inventory_ledger_page_v2(
  p_after_updated_at timestamptz default null,p_after_id uuid default null,p_limit integer default 250)
returns table(id uuid,stall_id uuid,business_day_id uuid,product_id uuid,
  quantity_delta numeric(12,3),sealed_packs_delta integer,unit_cost numeric(18,8),
  movement_type public.inventory_movement_type,reason text,reference_id uuid,
  occurred_at timestamptz,updated_at timestamptz,deleted_at timestamptz,
  counted_quantity numeric(12,3),counted_unit text)
language plpgsql stable security definer set search_path=public as $$
begin
  if public.current_app_role()<>'cashier' then raise exception 'CASHIER_REQUIRED' using errcode='42501'; end if;
  if p_limit<1 or p_limit>500 or (p_after_id is not null and p_after_updated_at is null) then
    raise exception 'INVALID_PAGE' using errcode='22023'; end if;
  if not exists(select 1 from public.devices d where d.stall_id=public.current_app_stall_id()
    and d.is_active and d.deleted_at is null
    and d.hardware_id=coalesce(current_setting('request.headers',true)::json->>'x-device-id','')) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode='42501'; end if;
  return query select l.id,l.stall_id,l.business_day_id,l.product_id,l.quantity_delta,
    l.sealed_packs_delta,l.unit_cost,l.movement_type,l.reason,l.reference_id,l.occurred_at,l.updated_at,l.deleted_at,l.counted_quantity,l.counted_unit
  from public.inventory_ledger l where l.stall_id=public.current_app_stall_id()
    and (p_after_updated_at is null or l.updated_at>p_after_updated_at
      or (p_after_id is not null and l.updated_at=p_after_updated_at and l.id>p_after_id))
  order by l.updated_at,l.id limit p_limit;
end;
$$;
revoke all on function public.get_pos_inventory_ledger_page_v2(timestamptz,uuid,integer) from public;
grant execute on function public.get_pos_inventory_ledger_page_v2(timestamptz,uuid,integer) to anon,authenticated;
