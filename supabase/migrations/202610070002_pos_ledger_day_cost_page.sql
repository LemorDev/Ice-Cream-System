-- Carry booked day and cost back to a phone during a catalog/ledger refresh.
create function public.get_pos_inventory_ledger_page_v2(
  p_after_updated_at timestamptz default null,
  p_after_id uuid default null,
  p_limit integer default 250
)
returns table (id uuid, stall_id uuid, business_day_id uuid, product_id uuid,
  quantity_delta numeric(12,3), unit_cost numeric(18,8),
  movement_type public.inventory_movement_type, reason text, reference_id uuid,
  occurred_at timestamptz, updated_at timestamptz, deleted_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if public.current_app_role()<>'cashier' then raise exception 'CASHIER_REQUIRED' using errcode='42501'; end if;
  if p_limit<1 or p_limit>500 or (p_after_id is not null and p_after_updated_at is null) then
    raise exception 'INVALID_PAGE' using errcode='22023';
  end if;
  if not exists(select 1 from public.devices d where d.stall_id=public.current_app_stall_id()
    and d.is_active and d.deleted_at is null
    and d.hardware_id=coalesce(current_setting('request.headers',true)::json->>'x-device-id','')) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode='42501';
  end if;
  return query select l.id,l.stall_id,l.business_day_id,l.product_id,l.quantity_delta,l.unit_cost,
    l.movement_type,l.reason,l.reference_id,l.occurred_at,l.updated_at,l.deleted_at
  from public.inventory_ledger l where l.stall_id=public.current_app_stall_id()
    and (p_after_updated_at is null or l.updated_at>p_after_updated_at
      or (p_after_id is not null and l.updated_at=p_after_updated_at and l.id>p_after_id))
  order by l.updated_at,l.id limit p_limit;
end;
$$;
revoke all on function public.get_pos_inventory_ledger_page_v2(timestamptz,uuid,integer) from public;
grant execute on function public.get_pos_inventory_ledger_page_v2(timestamptz,uuid,integer) to anon,authenticated;
