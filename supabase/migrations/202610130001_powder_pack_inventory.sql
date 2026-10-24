-- Sealed packs and operational grams share the existing gram-denominated stock ledger.
-- A pack opening changes the sealed count, but never changes total stock or COGS.
alter type public.inventory_movement_type add value if not exists 'pack_unsealed';
alter type public.inventory_movement_type add value if not exists 'pack_reclassified';

alter table public.products add column tracks_powder_packs boolean not null default false;
alter table public.products add constraint products_powder_pack_tracking_check
  check (not tracks_powder_packs or (product_type='raw' and base_unit='g' and pack_size=1000
    and conversion_rate=1 and purchase_unit_name is not null and lower(btrim(purchase_unit_name))='pack'));

alter table public.inventory_ledger add column sealed_packs_delta integer not null default 0;
alter table public.inventory_ledger drop constraint if exists inventory_ledger_nonzero_or_waste_check;
alter table public.inventory_ledger add constraint inventory_ledger_nonzero_or_waste_check
  check (quantity_delta<>0 or movement_type::text in ('void_waste','pack_unsealed','pack_reclassified'));

create function public.guard_powder_pack_ledger() returns trigger
language plpgsql security definer set search_path=public as $$
declare tracked boolean; sealed integer; total numeric;
begin
  select tracks_powder_packs into tracked from public.products where id=new.product_id and stall_id=new.stall_id for update;
  if not found then raise exception 'INVALID_PRODUCT' using errcode='22023'; end if;
  if new.movement_type::text='receive' and tracked then
    if new.quantity_delta<=0 or new.quantity_delta<>trunc(new.quantity_delta/1000)*1000 then
      raise exception 'POWDER_RECEIVE_WHOLE_PACKS_REQUIRED' using errcode='22023';
    end if;
    new.sealed_packs_delta := (new.quantity_delta/1000)::integer;
  elsif new.movement_type::text in ('pack_unsealed','pack_reclassified') then
    if not tracked or new.quantity_delta<>0 or new.sealed_packs_delta=0 then
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
    if new.movement_type::text='pack_reclassified' and total-(sealed+new.sealed_packs_delta)*1000<0 then
      raise exception 'INSUFFICIENT_OPERATIONAL_POWDER' using errcode='P0001';
    end if;
  elsif new.sealed_packs_delta<>0 then
    raise exception 'INVALID_SEALED_PACK_DELTA' using errcode='22023';
  end if;
  return new;
end;
$$;
create trigger guard_powder_pack_ledger_before_insert before insert on public.inventory_ledger
  for each row execute function public.guard_powder_pack_ledger();

-- Keep the established product save operation atomic with the tracking flag.
alter function public.save_product_with_recipe(jsonb,jsonb) rename to save_product_with_recipe_before_powder;
revoke all on function public.save_product_with_recipe_before_powder(jsonb,jsonb) from public,anon,authenticated;
create function public.save_product_with_recipe(p_product jsonb,p_recipe jsonb default '[]'::jsonb)
returns jsonb language plpgsql security definer set search_path=public,extensions as $$
declare saved jsonb; product_id uuid; track boolean; existing_track boolean;
begin
  product_id:=nullif(p_product->>'id','')::uuid;
  if not public.can_manage_stall((p_product->>'stall_id')::uuid) then
    raise exception 'FORBIDDEN' using errcode='42501';
  end if;
  select tracks_powder_packs into existing_track from public.products where id=product_id;
  track:=coalesce((p_product->>'tracks_powder_packs')::boolean,existing_track,false);
  if track and (p_product->>'product_type'<>'raw' or p_product->>'base_unit'<>'g'
      or coalesce(nullif(p_product->>'purchase_quantity','')::numeric,0)<>1000
      or lower(btrim(coalesce(p_product->>'purchase_unit_name','Pack')))<>'pack') then
    raise exception 'POWDER_REQUIRES_1000G_PACK' using errcode='22023';
  end if;
  if existing_track and not track then
    if (select coalesce(sum(sealed_packs_delta),0) from public.inventory_ledger
      where product_id=product_id and deleted_at is null)<>0 then
      raise exception 'RECLASSIFY_SEALED_PACKS_BEFORE_DISABLING' using errcode='22023';
    end if;
    update public.products set tracks_powder_packs=false where id=product_id;
  end if;
  saved:=public.save_product_with_recipe_before_powder(p_product,p_recipe);
  update public.products set tracks_powder_packs=track where id=(saved->>'id')::uuid;
  return (select to_jsonb(p) from public.products p where p.id=(saved->>'id')::uuid);
end;
$$;
revoke all on function public.save_product_with_recipe(jsonb,jsonb) from public;
grant execute on function public.save_product_with_recipe(jsonb,jsonb) to anon,authenticated;

-- IMS owners may reconcile the sealed count when tracking is first enabled.
create function public.set_powder_sealed_packs(p_product_id uuid,p_count integer,p_reason text default 'Sealed pack count')
returns integer language plpgsql security definer set search_path=public,extensions as $$
declare product_row public.products%rowtype; current_count integer; difference integer;
begin
  select * into product_row from public.products where id=p_product_id and deleted_at is null for update;
  if not found or not public.can_manage_stall(product_row.stall_id) then raise exception 'FORBIDDEN' using errcode='42501'; end if;
  if not product_row.tracks_powder_packs or p_count<0 or p_count is null or length(coalesce(p_reason,''))>500 then
    raise exception 'INVALID_SEALED_PACK_COUNT' using errcode='22023';
  end if;
  select coalesce(sum(sealed_packs_delta),0) into current_count from public.inventory_ledger
    where stall_id=product_row.stall_id and product_id=p_product_id and deleted_at is null;
  difference:=p_count-current_count;
  if difference<>0 then
    insert into public.inventory_ledger(stall_id,product_id,quantity_delta,sealed_packs_delta,movement_type,reason)
    values(product_row.stall_id,p_product_id,0,difference,'pack_reclassified',nullif(btrim(p_reason),''));
  end if;
  return p_count;
end;
$$;
revoke all on function public.set_powder_sealed_packs(uuid,integer,text) from public;
grant execute on function public.set_powder_sealed_packs(uuid,integer,text) to anon,authenticated;

-- Cashier device uploads one locally committed opening; the UUID makes retries safe.
create function public.push_pos_pack_unseal(p_entry jsonb) returns jsonb
language plpgsql security definer set search_path=public,private,extensions as $$
declare stall_value uuid:=public.current_app_stall_id(); day_value uuid:=nullif(p_entry->>'business_day_id','')::uuid;
  entry_value uuid:=nullif(p_entry->>'id','')::uuid; product_value uuid:=nullif(p_entry->>'product_id','')::uuid;
  count_value integer:=nullif(p_entry->>'packs_opened','')::integer; existing public.inventory_ledger%rowtype;
begin
  if public.current_app_role()<>'cashier' or stall_value is null or day_value is null or entry_value is null
    or product_value is null or count_value is null or count_value<=0 or count_value>1000
    or nullif(p_entry->>'stall_id','')::uuid is distinct from stall_value then
    raise exception 'INVALID_PACK_OPENING' using errcode='22023';
  end if;
  perform 1 from public.business_days d where d.id=day_value and d.stall_id=stall_value
    and d.deleted_at is null and public.active_pos_device(d.device_id)
    and (p_entry->>'occurred_at')::timestamptz>=d.opened_at
    and (p_entry->>'occurred_at')::timestamptz<=coalesce(d.closed_at,'infinity'::timestamptz);
  if not found then raise exception 'OPERATING_DAY_MISMATCH' using errcode='22023'; end if;
  select * into existing from public.inventory_ledger where id=entry_value for update;
  if found then
    if existing.stall_id is distinct from stall_value or existing.business_day_id is distinct from day_value
      or existing.product_id is distinct from product_value or existing.movement_type::text<>'pack_unsealed'
      or existing.sealed_packs_delta is distinct from -count_value or existing.quantity_delta<>0
      or existing.occurred_at is distinct from (p_entry->>'occurred_at')::timestamptz then
      raise exception 'PACK_OPENING_IDEMPOTENCY_CONFLICT' using errcode='23505';
    end if;
    return jsonb_build_object('status','duplicate','ledger_id',entry_value);
  end if;
  insert into public.inventory_ledger(id,stall_id,business_day_id,product_id,quantity_delta,sealed_packs_delta,
    movement_type,reason,occurred_at)
  values(entry_value,stall_value,day_value,product_value,0,-count_value,'pack_unsealed','Powder pack opened',
    (p_entry->>'occurred_at')::timestamptz);
  return jsonb_build_object('status','accepted','ledger_id',entry_value);
end;
$$;
revoke all on function public.push_pos_pack_unseal(jsonb) from public;
grant execute on function public.push_pos_pack_unseal(jsonb) to anon,authenticated;

-- Preserve the existing consistent financial snapshot and add the two powder fields.
alter function public.get_ims_financial_snapshot(uuid) rename to get_ims_financial_snapshot_before_powder;
revoke all on function public.get_ims_financial_snapshot_before_powder(uuid) from public,anon,authenticated;
create function public.get_ims_financial_snapshot(p_stall_id uuid) returns jsonb
language plpgsql stable security definer set search_path=public,private,extensions as $$
declare result jsonb;
begin
  result:=public.get_ims_financial_snapshot_before_powder(p_stall_id);
  result:=jsonb_set(result,'{products}',coalesce((select jsonb_agg(item||jsonb_build_object(
    'tracks_powder_packs',p.tracks_powder_packs)) from jsonb_array_elements(result->'products') item
    join public.products p on p.id=(item->>'id')::uuid),'[]'::jsonb));
  result:=jsonb_set(result,'{inventory}',coalesce((select jsonb_agg(item||jsonb_build_object(
    'sealed_packs_delta',l.sealed_packs_delta)) from jsonb_array_elements(result->'inventory') item
    join public.inventory_ledger l on l.id=(item->>'id')::uuid),'[]'::jsonb));
  return result;
end;
$$;
revoke all on function public.get_ims_financial_snapshot(uuid) from public;
grant execute on function public.get_ims_financial_snapshot(uuid) to anon,authenticated;

drop function public.get_pos_products_page(timestamptz,uuid,integer);
create function public.get_pos_products_page(
  p_after_updated_at timestamptz default null,p_after_id uuid default null,p_limit integer default 250)
returns table(id uuid,stall_id uuid,category_id uuid,category_name text,sku text,name text,
  unit text,sale_price numeric(12,2),cost_price numeric(10,4),low_stock_threshold numeric(12,3),
  pack_size numeric(12,3),conversion_rate numeric(12,3),is_sellable boolean,product_type text,
  base_unit text,tracks_powder_packs boolean,updated_at timestamptz,deleted_at timestamptz)
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
    p.is_sellable,p.product_type,p.base_unit,p.tracks_powder_packs,p.updated_at,p.deleted_at
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

drop function public.get_pos_inventory_ledger_page_v2(timestamptz,uuid,integer);
create function public.get_pos_inventory_ledger_page_v2(
  p_after_updated_at timestamptz default null,p_after_id uuid default null,p_limit integer default 250)
returns table(id uuid,stall_id uuid,business_day_id uuid,product_id uuid,
  quantity_delta numeric(12,3),sealed_packs_delta integer,unit_cost numeric(18,8),
  movement_type public.inventory_movement_type,reason text,reference_id uuid,
  occurred_at timestamptz,updated_at timestamptz,deleted_at timestamptz)
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
    l.sealed_packs_delta,l.unit_cost,l.movement_type,l.reason,l.reference_id,l.occurred_at,l.updated_at,l.deleted_at
  from public.inventory_ledger l where l.stall_id=public.current_app_stall_id()
    and (p_after_updated_at is null or l.updated_at>p_after_updated_at
      or (p_after_id is not null and l.updated_at=p_after_updated_at and l.id>p_after_id))
  order by l.updated_at,l.id limit p_limit;
end;
$$;
revoke all on function public.get_pos_inventory_ledger_page_v2(timestamptz,uuid,integer) from public;
grant execute on function public.get_pos_inventory_ledger_page_v2(timestamptz,uuid,integer) to anon,authenticated;
