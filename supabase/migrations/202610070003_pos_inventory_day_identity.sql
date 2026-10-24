-- Stock receipts and the sale-ledger acknowledgement carry their day identity.
create function public.push_pos_inventory_entry_v2(p_entry jsonb)
returns jsonb language plpgsql security definer set search_path = public, private, extensions as $$
declare
  stall_value uuid := public.current_app_stall_id();
  day_value uuid := nullif(p_entry->>'business_day_id','')::uuid;
  entry_value uuid := nullif(p_entry->>'id','')::uuid;
  existing public.inventory_ledger%rowtype;
  result jsonb;
begin
  if public.current_app_role()<>'cashier' or stall_value is null or day_value is null or entry_value is null
    or nullif(p_entry->>'stall_id','')::uuid is distinct from stall_value then
    raise exception 'INVALID_STOCK_IDENTITY' using errcode='42501';
  end if;
  perform 1 from public.business_days d where d.id=day_value and d.stall_id=stall_value
    and d.deleted_at is null and public.active_pos_device(d.device_id)
    and (p_entry->>'occurred_at')::timestamptz>=d.opened_at
    and (p_entry->>'occurred_at')::timestamptz<=coalesce(d.closed_at,'infinity'::timestamptz);
  if not found then raise exception 'OPERATING_DAY_MISMATCH' using errcode='22023'; end if;
  select * into existing from public.inventory_ledger where id=entry_value for update;
  if found then
    if existing.stall_id is distinct from stall_value or existing.business_day_id is distinct from day_value
      or existing.product_id is distinct from (p_entry->>'product_id')::uuid
      or existing.quantity_delta is distinct from (p_entry->>'quantity_delta')::numeric
      or existing.movement_type::text is distinct from p_entry->>'movement_type'
      or existing.reason is distinct from nullif(p_entry->>'reason','')
      or existing.reference_id is distinct from nullif(p_entry->>'reference_id','')::uuid
      or existing.occurred_at is distinct from (p_entry->>'occurred_at')::timestamptz then
      raise exception 'STOCK_ENTRY_IDEMPOTENCY_CONFLICT' using errcode='23505';
    end if;
    return jsonb_build_object('status','duplicate','ledger_id',entry_value);
  end if;
  result := public.push_pos_inventory_entry(p_entry);
  select * into existing from public.inventory_ledger where id=entry_value;
  if not found or existing.business_day_id is distinct from day_value
    or (result->>'ledger_id')::uuid is distinct from entry_value then
    raise exception 'STOCK_ENTRY_DAY_OR_ID_MISMATCH' using errcode='23505';
  end if;
  return result;
end;
$$;
revoke all on function public.push_pos_inventory_entry_v2(jsonb) from public;
grant execute on function public.push_pos_inventory_entry_v2(jsonb) to anon,authenticated;
