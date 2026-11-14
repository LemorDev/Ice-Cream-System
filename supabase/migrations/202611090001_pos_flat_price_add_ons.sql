-- Allow one generic PHP 5 add-on per serving without changing stock or COGS.
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
        p.product_type, p.base_unit, p.updated_at, p.deleted_at, p.image_url,
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
    'revision_id', revision.id, 'valid_until', revision.valid_until, 'device_id', device.id, 'add_on_price_cents', 500
  );
end;
$$;
revoke all on function public.get_pos_catalog_revision() from public;
grant execute on function public.get_pos_catalog_revision() to anon, authenticated;

-- Every new v2 sale records its original upload inside the same transaction.
-- Rejecting here rolls back the sale, component and ledger writes together.
-- Previously accepted uploads continue to use the existing exact-replay check.
create or replace function private.verify_pos_sale_catalog()
returns trigger
language plpgsql set search_path = pg_catalog as $$
declare
  snapshot jsonb;
begin
  select r.catalog into snapshot from private.pos_catalog_revisions r
  where r.id = nullif(new.payload->>'catalog_revision_id', '')::uuid
    and r.stall_id = new.stall_id
    and r.device_id = (new.payload->>'device_id')::uuid
    and r.valid_until > statement_timestamp();
  if not found then
    raise exception 'SALE_CATALOG_REVISION_REQUIRED' using errcode = '22023';
  end if;

  if exists (
    select 1 from jsonb_to_recordset(new.payload->'items') i(product_id uuid, product_name text, quantity numeric, unit_price numeric)
    left join jsonb_to_recordset(snapshot->'products') p(id uuid, name text, is_sellable boolean, deleted_at timestamptz, sale_price numeric)
      on p.id = i.product_id and p.deleted_at is null
    where p.is_sellable is not true or not (
      i.unit_price is not distinct from p.sale_price or (
        i.product_name is not distinct from (p.name || ' + Add-on')
        and i.unit_price is not distinct from (p.sale_price + 5.00)
      )
    )
      or i.quantity is distinct from trunc(i.quantity) or i.quantity <= 0
  ) then
    raise exception 'SALE_CATALOG_PRICE_MISMATCH' using errcode = '22023';
  end if;

  if exists (
    with items as (
      select * from jsonb_to_recordset(new.payload->'items') i(product_id uuid, quantity numeric)
    ), recipes as (
      select * from jsonb_to_recordset(snapshot->'recipes') r(parent_product_id uuid, ingredient_product_id uuid, quantity numeric)
    ), products as (
      select * from jsonb_to_recordset(snapshot->'products') p(id uuid, deleted_at timestamptz,
        tracks_powder_packs boolean, cost_price numeric, pack_size numeric, conversion_rate numeric)
    ), required as (
      select coalesce(r.ingredient_product_id, i.product_id) as product_id,
        sum(i.quantity * coalesce(r.quantity, 1)) as quantity
      from items i left join recipes r on r.parent_product_id = i.product_id
      group by coalesce(r.ingredient_product_id, i.product_id)
    ), expected as (
      select r.product_id, r.quantity,
        round(r.quantity * p.cost_price / nullif(p.pack_size * p.conversion_rate, 0), 2) as cost_total
      from required r left join products p on p.id = r.product_id and p.deleted_at is null
      where p.tracks_powder_packs is not true
    ), submitted as (
      select * from jsonb_to_recordset(new.payload->'components') c(product_id uuid, quantity numeric, cost_total numeric)
    )
    select 1 from expected e full join submitted s using (product_id)
    where e.product_id is null or s.product_id is null or e.cost_total is null
      or s.quantity is distinct from e.quantity or s.cost_total is distinct from e.cost_total
  ) then
    raise exception 'SALE_CATALOG_COMPONENT_MISMATCH' using errcode = '22023';
  end if;
  return new;
end;
$$;
revoke all on function private.verify_pos_sale_catalog() from public, anon, authenticated;
