-- Keep historical gram products valid and use one base unit for new piece-based packs.

alter table public.products drop constraint products_powder_pack_tracking_check;

alter table public.products add constraint products_powder_pack_tracking_check
  check (not tracks_powder_packs or (product_type='raw' and conversion_rate=1
    and ((base_unit='g' and pack_size=1000) or (base_unit='piece' and pack_size=1))
    and purchase_unit_name is not null and lower(btrim(purchase_unit_name))='pack'));

create or replace function public.guard_powder_pack_ledger() returns trigger
language plpgsql security definer set search_path=public as $$
declare tracked boolean; sealed integer; total numeric; units_per_pack numeric;
begin
  select tracks_powder_packs,pack_size*conversion_rate into tracked,units_per_pack from public.products where id=new.product_id and stall_id=new.stall_id for update;
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
    if new.movement_type='pack_unsealed' and new.quantity_delta<0 and total+new.quantity_delta<0 then
      raise exception 'INSUFFICIENT_STOCK' using errcode='P0001';
    end if;
    if new.movement_type::text='pack_reclassified' and total-(sealed+new.sealed_packs_delta)*units_per_pack<0 then
      raise exception 'INSUFFICIENT_OPERATIONAL_POWDER' using errcode='P0001';
    end if;
  elsif new.sealed_packs_delta<>0 then
    raise exception 'INVALID_SEALED_PACK_DELTA' using errcode='22023';
  end if;
  return new;
end;
$$;

create or replace function public.push_pos_powder_opening(p_entry jsonb) returns jsonb
language plpgsql security definer set search_path=public,private,extensions as $$
declare stall_value uuid:=public.current_app_stall_id(); day_value uuid:=nullif(p_entry->>'business_day_id','')::uuid;
  entry_value uuid:=nullif(p_entry->>'id','')::uuid; product_value uuid:=nullif(p_entry->>'product_id','')::uuid;
  count_value integer:=nullif(p_entry->>'packs_opened','')::integer;
  cost_value numeric(12,2):=(p_entry->>'cost_total')::numeric(12,2);
  quantity_value numeric;
  unit_cost_value numeric(18,8);
  existing public.inventory_ledger%rowtype;
begin
  if public.current_app_role() is distinct from 'cashier' or stall_value is null
    or nullif(p_entry->>'stall_id','')::uuid is distinct from stall_value then
    raise exception 'ACTIVE_CASHIER_DEVICE_REQUIRED' using errcode='42501';
  end if;
  if day_value is null or entry_value is null or product_value is null
    or count_value is null or count_value<=0 or count_value>1000
    or cost_value is null or cost_value<0 or cost_value='NaN'::numeric then
    raise exception 'INVALID_PACK_OPENING' using errcode='22023';
  end if;
  perform 1 from public.business_days d where d.id=day_value and d.stall_id=stall_value
    and d.deleted_at is null and public.active_pos_device(d.device_id)
    and (p_entry->>'occurred_at')::timestamptz>=d.opened_at
    and (p_entry->>'occurred_at')::timestamptz<=coalesce(d.closed_at,'infinity'::timestamptz) for update;
  if not found then raise exception 'OPERATING_DAY_MISMATCH' using errcode='22023'; end if;
  select -count_value*p.pack_size*p.conversion_rate into quantity_value
    from public.products p where p.id=product_value and p.stall_id=stall_value for update;
  if not found then raise exception 'INVALID_PRODUCT' using errcode='22023'; end if;
  unit_cost_value:=round(cost_value/nullif(-quantity_value,0),8);
  select * into existing from public.inventory_ledger where id=entry_value for update;
  if found then
    if existing.stall_id is distinct from stall_value or existing.business_day_id is distinct from day_value
      or existing.product_id is distinct from product_value or existing.movement_type::text<>'pack_unsealed'
      or existing.sealed_packs_delta is distinct from -count_value or existing.quantity_delta<>quantity_value
      or existing.unit_cost is distinct from unit_cost_value
      or existing.occurred_at is distinct from (p_entry->>'occurred_at')::timestamptz then
      raise exception 'PACK_OPENING_IDEMPOTENCY_CONFLICT' using errcode='23505';
    end if;
    return jsonb_build_object('status','duplicate','ledger_id',entry_value);
  end if;
  insert into public.inventory_ledger(id,stall_id,business_day_id,product_id,quantity_delta,sealed_packs_delta,
    movement_type,reason,occurred_at,unit_cost)
  values(entry_value,stall_value,day_value,product_value,quantity_value,-count_value,'pack_unsealed','Powder pack opened',
    (p_entry->>'occurred_at')::timestamptz,unit_cost_value);
  update public.daily_store_closings c set cogs=c.cogs+cost_value, net_profit=c.net_profit-cost_value
    where c.business_day_id=day_value;
  return jsonb_build_object('status','accepted','ledger_id',entry_value);
end;
$$;

create or replace function public.save_product_with_recipe(p_product jsonb,p_recipe jsonb default '[]'::jsonb)
returns jsonb language plpgsql security definer set search_path=public,extensions as $$
declare
  saved jsonb;
  requested_id uuid:=nullif(p_product->>'id','')::uuid;
  requested_stall uuid:=(p_product->>'stall_id')::uuid;
  track boolean;
  existing_track boolean;
  existing_unit text;
begin
  if not public.can_manage_stall(requested_stall) then
    raise exception 'FORBIDDEN' using errcode='42501';
  end if;
  select p.tracks_powder_packs,p.base_unit into existing_track,existing_unit from public.products p
    where p.id=requested_id and p.stall_id=requested_stall and p.deleted_at is null for update;
  track:=coalesce((p_product->>'tracks_powder_packs')::boolean,existing_track,false);
  if existing_unit is distinct from p_product->>'base_unit' and exists(
    select 1 from public.inventory_ledger l where l.product_id=requested_id
      and l.stall_id=requested_stall
  ) and (track or existing_track or exists(select 1 from public.inventory_ledger l
    where l.product_id=requested_id and l.stall_id=requested_stall
      and l.movement_type in ('pack_unsealed','pack_reclassified'))) then
    raise exception 'CREATE_A_NEW_POWDER_PRODUCT_WHEN_CHANGING_STOCK_UNITS' using errcode='22023';
  end if;
  if track and (p_product->>'product_type'<>'raw' or p_product->>'base_unit' not in ('g','piece')
      or coalesce(nullif(p_product->>'purchase_quantity','')::numeric,
        (p_product->>'pack_size')::numeric*(p_product->>'conversion_rate')::numeric,0)<>case when p_product->>'base_unit'='piece' then 1 else 1000 end
      or lower(btrim(coalesce(p_product->>'purchase_unit_name','Pack')))<>'pack') then
    raise exception 'POWDER_REQUIRES_ONE_PIECE_OR_1000G_PER_PACK' using errcode='22023';
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
