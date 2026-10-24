-- Fix JSON text-to-enum conversion for stock receipt inserts and sale duplicate checks.
create or replace function public.push_pos_inventory_entry(p_entry jsonb)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare current_stall uuid := public.current_app_stall_id(); saved_id uuid;
begin
  if public.current_app_role() is distinct from 'cashier' or current_stall is null or (p_entry ->> 'stall_id')::uuid is distinct from current_stall then
    raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if p_entry ->> 'movement_type' = 'receive' then
    if not exists (select 1 from public.devices d where d.stall_id = current_stall
      and d.is_active and d.deleted_at is null
      and d.hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '')) then
      raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
    end if;
    if not exists (select 1 from public.products where id = (p_entry ->> 'product_id')::uuid
        and stall_id = current_stall and deleted_at is null and product_type in ('raw', 'packaging'))
      or coalesce((p_entry ->> 'quantity_delta')::numeric, 0) <= 0
      or (p_entry ->> 'quantity_delta')::numeric > 999999999.999
      or (p_entry ->> 'quantity_delta')::numeric <> round((p_entry ->> 'quantity_delta')::numeric, 3)
      or length(coalesce(p_entry ->> 'reason', '')) > 500
      or nullif(p_entry ->> 'reference_id', '') is not null then
      raise exception 'INVALID_STOCK_RECEIPT' using errcode = '22023';
    end if;
  elsif (p_entry ->> 'movement_type') is distinct from 'sale' or (p_entry ->> 'quantity_delta')::numeric >= 0
    or not exists (select 1 from public.transactions t where t.id = nullif(p_entry ->> 'reference_id', '')::uuid
      and t.stall_id = current_stall and t.cashier_id = public.current_app_user_id()) then
    raise exception 'INVALID_LEDGER_ENTRY' using errcode = '22023'; end if;
  if p_entry ->> 'movement_type' = 'sale' then
  select id into saved_id from public.inventory_ledger
  where stall_id = current_stall and product_id = (p_entry ->> 'product_id')::uuid
    and movement_type = (p_entry ->> 'movement_type')::public.inventory_movement_type
    and reference_id is not distinct from nullif(p_entry ->> 'reference_id', '')::uuid
    and deleted_at is null limit 1;
  if saved_id is not null then return jsonb_build_object('status', 'duplicate', 'ledger_id', saved_id); end if;
  end if;
  insert into public.inventory_ledger (id, stall_id, product_id, quantity_delta, movement_type, reason, reference_id, occurred_at)
  values ((p_entry ->> 'id')::uuid, current_stall, (p_entry ->> 'product_id')::uuid,
    (p_entry ->> 'quantity_delta')::numeric, (p_entry ->> 'movement_type')::public.inventory_movement_type, nullif(p_entry ->> 'reason', ''),
    nullif(p_entry ->> 'reference_id', '')::uuid, (p_entry ->> 'occurred_at')::timestamptz)
  on conflict (id) do nothing returning id into saved_id;
  return jsonb_build_object('status', case when saved_id is null then 'duplicate' else 'accepted' end,
    'ledger_id', coalesce(saved_id, (p_entry ->> 'id')::uuid));
end;
$$;
