-- Forward repair for the already-applied powder migration. Qualify product
-- identity and serialize tracking edits/openings with receipts and day closing.
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
  update public.products p set tracks_powder_packs=track where p.id=(saved->>'id')::uuid;
  return (select to_jsonb(p) from public.products p where p.id=(saved->>'id')::uuid);
end;
$$;
revoke all on function public.save_product_with_recipe(jsonb,jsonb) from public;
grant execute on function public.save_product_with_recipe(jsonb,jsonb) to anon,authenticated;

create or replace function public.push_pos_pack_unseal(p_entry jsonb) returns jsonb
language plpgsql security definer set search_path=public,private,extensions as $$
declare stall_value uuid:=public.current_app_stall_id(); day_value uuid:=nullif(p_entry->>'business_day_id','')::uuid;
  entry_value uuid:=nullif(p_entry->>'id','')::uuid; product_value uuid:=nullif(p_entry->>'product_id','')::uuid;
  count_value integer:=nullif(p_entry->>'packs_opened','')::integer; existing public.inventory_ledger%rowtype;
begin
  if public.current_app_role() is distinct from 'cashier' or stall_value is null
    or nullif(p_entry->>'stall_id','')::uuid is distinct from stall_value then
    raise exception 'ACTIVE_CASHIER_DEVICE_REQUIRED' using errcode='42501';
  end if;
  if day_value is null or entry_value is null or product_value is null
    or count_value is null or count_value<=0 or count_value>1000 then
    raise exception 'INVALID_PACK_OPENING' using errcode='22023';
  end if;
  perform 1 from public.business_days d where d.id=day_value and d.stall_id=stall_value
    and d.deleted_at is null and public.active_pos_device(d.device_id)
    and (p_entry->>'occurred_at')::timestamptz>=d.opened_at
    and (p_entry->>'occurred_at')::timestamptz<=coalesce(d.closed_at,'infinity'::timestamptz) for update;
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
