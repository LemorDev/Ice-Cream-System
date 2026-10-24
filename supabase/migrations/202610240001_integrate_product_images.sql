-- Integrate product photos without replacing paged catalogs, recipe saves, or powder tracking.
begin;

create or replace function public.save_product_with_recipe(p_product jsonb,p_recipe jsonb default '[]'::jsonb)
returns jsonb language plpgsql security definer set search_path=public,extensions as $$
declare
  saved jsonb;
  requested_id uuid:=nullif(p_product->>'id','')::uuid;
  requested_stall uuid:=(p_product->>'stall_id')::uuid;
  track boolean;
  existing_track boolean;
begin
  if not public.can_manage_stall(requested_stall) then
    raise exception 'FORBIDDEN' using errcode='42501';
  end if;
  select p.tracks_powder_packs into existing_track from public.products p
    where p.id=requested_id and p.stall_id=requested_stall and p.deleted_at is null for update;
  track:=coalesce((p_product->>'tracks_powder_packs')::boolean,existing_track,false);
  if track and (p_product->>'product_type'<>'raw' or p_product->>'base_unit'<>'g'
      or coalesce(nullif(p_product->>'purchase_quantity','')::numeric,
        (p_product->>'pack_size')::numeric*(p_product->>'conversion_rate')::numeric,0)<>1000
      or lower(btrim(coalesce(p_product->>'purchase_unit_name','Pack')))<>'pack') then
    raise exception 'POWDER_REQUIRES_1000G_PACK' using errcode='22023';
  end if;
  if existing_track and not track then
    if (select coalesce(sum(l.sealed_packs_delta),0) from public.inventory_ledger l
      where l.product_id=requested_id and l.stall_id=requested_stall and l.deleted_at is null)<>0 then
      raise exception 'RECLASSIFY_SEALED_PACKS_BEFORE_DISABLING' using errcode='22023';
    end if;
    update public.products p set tracks_powder_packs=false where p.id=requested_id and p.stall_id=requested_stall;
  end if;
  saved:=public.save_product_with_recipe_before_powder(p_product,p_recipe);
  update public.products p set tracks_powder_packs=track,
    image_url=case when p_product ? 'image_url' then nullif(btrim(p_product->>'image_url'),'') else p.image_url end
    where p.id=(saved->>'id')::uuid;
  return (select to_jsonb(p) from public.products p where p.id=(saved->>'id')::uuid);
end;
$$;
revoke all on function public.save_product_with_recipe(jsonb,jsonb) from public;
grant execute on function public.save_product_with_recipe(jsonb,jsonb) to anon,authenticated;


drop function public.get_pos_products_page(timestamptz,uuid,integer);
create function public.get_pos_products_page(
  p_after_updated_at timestamptz default null,p_after_id uuid default null,p_limit integer default 250)
returns table(id uuid,stall_id uuid,category_id uuid,category_name text,sku text,name text,
  unit text,sale_price numeric(12,2),cost_price numeric(10,4),low_stock_threshold numeric(12,3),
  pack_size numeric(12,3),conversion_rate numeric(12,3),is_sellable boolean,product_type text,
  base_unit text,tracks_powder_packs boolean,image_url text,updated_at timestamptz,deleted_at timestamptz)
language plpgsql stable security definer set search_path=public as $$
begin
  if public.current_app_role()<>'cashier' then raise exception 'CASHIER_REQUIRED' using errcode='42501'; end if;
  if p_limit<1 or p_limit>500 or (p_after_id is not null and p_after_updated_at is null) then
    raise exception 'INVALID_PAGE' using errcode='22023'; end if;
  if not exists(select 1 from public.devices d where d.stall_id=public.current_app_stall_id()
    and d.is_active and d.deleted_at is null
    and d.hardware_id=coalesce(current_setting('request.headers',true)::json->>'x-device-id','')) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode='42501'; end if;
  return query select p.id,p.stall_id,p.category_id,
    coalesce(nullif(btrim(p.sell_category),''),c.name,'Uncategorized'),p.sku,p.name,p.unit,
    p.sale_price,p.cost_price,p.low_stock_threshold,p.pack_size,p.conversion_rate,
    p.is_sellable,p.product_type,p.base_unit,p.tracks_powder_packs,p.image_url,p.updated_at,p.deleted_at
  from public.products p left join public.product_categories c on c.id=p.category_id
    and c.stall_id=p.stall_id and c.deleted_at is null
  where p.stall_id=public.current_app_stall_id()
    and (p_after_updated_at is null or p.updated_at>p_after_updated_at
      or (p_after_id is not null and p.updated_at=p_after_updated_at and p.id>p_after_id))
  order by p.updated_at,p.id limit p_limit;
end;
$$;
revoke all on function public.get_pos_products_page(timestamptz,uuid,integer) from public;
grant execute on function public.get_pos_products_page(timestamptz,uuid,integer) to anon,authenticated;


create or replace function public.get_ims_financial_snapshot(p_stall_id uuid) returns jsonb
language plpgsql stable security definer set search_path=public,private,extensions as $$
declare result jsonb;
begin
  result:=public.get_ims_financial_snapshot_before_powder(p_stall_id);
  result:=jsonb_set(result,'{products}',coalesce((select jsonb_agg(item||jsonb_build_object(
    'tracks_powder_packs',p.tracks_powder_packs,'image_url',p.image_url)) from jsonb_array_elements(result->'products') item
    join public.products p on p.id=(item->>'id')::uuid),'[]'::jsonb));
  result:=jsonb_set(result,'{inventory}',coalesce((select jsonb_agg(item||jsonb_build_object(
    'sealed_packs_delta',l.sealed_packs_delta)) from jsonb_array_elements(result->'inventory') item
    join public.inventory_ledger l on l.id=(item->>'id')::uuid),'[]'::jsonb));
  return result;
end;
$$;
revoke all on function public.get_ims_financial_snapshot(uuid) from public;
grant execute on function public.get_ims_financial_snapshot(uuid) to anon,authenticated;


drop function public.get_pos_products(timestamptz);
create function public.get_pos_products(p_updated_after timestamptz default null)
returns table (
  id uuid, stall_id uuid, category_id uuid, category_name text, sku text, name text, unit text,
  sale_price numeric(12,2), low_stock_threshold numeric(12,3), pack_size numeric(12,3),
  conversion_rate numeric(12,3), is_sellable boolean, product_type text, base_unit text, tracks_powder_packs boolean, image_url text,
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
    p.product_type, p.base_unit, p.tracks_powder_packs, p.image_url, p.updated_at, p.deleted_at
  from public.products p left join public.product_categories c on c.id = p.category_id and c.deleted_at is null
  where p.stall_id = public.current_app_stall_id() and (p_updated_after is null or p.updated_at > p_updated_after)
  order by p.updated_at, p.id;
end;
$$;
revoke all on function public.get_pos_products(timestamptz) from public;
grant execute on function public.get_pos_products(timestamptz) to anon,authenticated;
commit;
