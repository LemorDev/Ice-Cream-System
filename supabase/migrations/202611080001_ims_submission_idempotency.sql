create table private.ims_submissions (
  user_id uuid not null,
  request_id uuid not null,
  stall_id uuid not null,
  action text not null,
  payload jsonb not null,
  result jsonb,
  primary key (user_id, request_id)
);
alter table private.ims_submissions enable row level security;
revoke all on private.ims_submissions from public, anon, authenticated;

create function public.submit_ims_change(p_request_id uuid, p_stall_id uuid, p_action text, p_payload jsonb)
returns jsonb language plpgsql security definer set search_path = pg_catalog as $$
declare
  actor_id uuid := public.current_app_user_id();
  submission private.ims_submissions%rowtype;
  product_id_value uuid;
  movement public.inventory_movement_type;
  delta numeric;
  reason_value text;
  entry public.inventory_ledger%rowtype;
  response jsonb;
begin
  if actor_id is null or not public.can_manage_stall(p_stall_id) then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;
  if p_request_id is null or jsonb_typeof(p_payload) is distinct from 'object'
    or p_action is null or p_action not in ('create_product', 'stock_movement', 'stock_count', 'sealed_pack_count') then
    raise exception 'INVALID_IMS_SUBMISSION' using errcode = '22023';
  end if;

  -- The unique key serializes concurrent submissions. The reservation, write
  -- and saved response commit together, so a failed write leaves the key free.
  insert into private.ims_submissions(user_id, request_id, stall_id, action, payload)
  values (actor_id, p_request_id, p_stall_id, p_action, p_payload)
  on conflict (user_id, request_id) do nothing;
  if not found then
    select * into strict submission from private.ims_submissions s
    where s.user_id = actor_id and s.request_id = p_request_id;
    if submission.stall_id <> p_stall_id or submission.action <> p_action or submission.payload <> p_payload then
      raise exception 'IMS_IDEMPOTENCY_CONFLICT' using errcode = '23505';
    end if;
    return submission.result;
  end if;

  if p_action = 'create_product' then
    if jsonb_typeof(p_payload->'product') is distinct from 'object'
      or jsonb_typeof(p_payload->'recipe') is distinct from 'array' then
      raise exception 'INVALID_IMS_SUBMISSION' using errcode = '22023';
    end if;
    response := public.save_product_with_recipe(
      (p_payload->'product') || jsonb_build_object('id', null, 'stall_id', p_stall_id),
      p_payload->'recipe'
    );
  else
    product_id_value := (p_payload->>'product_id')::uuid;
    reason_value := nullif(btrim(p_payload->>'reason'), '');
    -- Use the same product lock as stock counts and POS stock writes.
    perform 1 from public.products p where p.id = product_id_value and p.stall_id = p_stall_id
      and p.deleted_at is null and p.product_type in ('raw', 'packaging') for update;
    if not found then
      raise exception 'STOCKABLE_PRODUCT_REQUIRED' using errcode = '22023';
    end if;

    case p_action
      when 'stock_count' then
        response := public.set_stock_on_hand(p_stall_id, product_id_value, (p_payload->>'target_stock')::numeric, reason_value);
      when 'sealed_pack_count' then
        response := to_jsonb(public.set_powder_sealed_packs(product_id_value, (p_payload->>'count')::integer, reason_value));
      when 'stock_movement' then
        movement := (p_payload->>'movement_type')::public.inventory_movement_type;
        delta := (p_payload->>'quantity_delta')::numeric(12,3);
        if reason_value is null or movement is null or movement not in ('receive', 'adjustment')
          or delta is null or delta = 0 or (movement = 'receive' and delta < 0) then
          raise exception 'INVALID_STOCK_MOVEMENT' using errcode = '22023';
        end if;
        if delta < 0 and public.get_stock_on_hand(p_stall_id, product_id_value) + delta < 0 then
          raise exception 'This adjustment would make stock negative.' using errcode = '22023';
        end if;
        insert into public.inventory_ledger(stall_id, product_id, quantity_delta, movement_type, reason)
        values (p_stall_id, product_id_value, delta, movement, reason_value)
        returning * into entry;
        response := to_jsonb(entry);
    end case;
  end if;

  update private.ims_submissions set result = response where user_id = actor_id and request_id = p_request_id;
  return response;
end;
$$;
revoke all on function public.submit_ims_change(uuid, uuid, text, jsonb) from public;
grant execute on function public.submit_ims_change(uuid, uuid, text, jsonb) to anon, authenticated;
