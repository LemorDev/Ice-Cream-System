-- The pre-release POS RPC treated a receipt/total collision as a duplicate.
-- Keep its validation and stock posting, but require the same immutable upload
-- document before acknowledging any replay. No live production sales exist yet.
create table private.pos_sale_uploads (
  transaction_id uuid primary key references public.transactions(id) on delete cascade,
  stall_id uuid not null references public.stalls(id),
  payload jsonb not null
);
alter table private.pos_sale_uploads enable row level security;
revoke all on private.pos_sale_uploads from public, anon, authenticated;

alter function public.push_pos_transaction(jsonb) rename to push_pos_transaction_device_guarded;
revoke all on function public.push_pos_transaction_device_guarded(jsonb) from public, anon, authenticated;

create function public.push_pos_transaction(p_transaction jsonb)
returns jsonb
language plpgsql security definer set search_path = public, private as $$
declare
  requested_id uuid := nullif(p_transaction ->> 'id', '')::uuid;
  requested_stall uuid := public.current_app_stall_id();
  saved public.transactions%rowtype;
  uploaded private.pos_sale_uploads%rowtype;
  result jsonb;
begin
  if public.current_app_role() <> 'cashier' then
    raise exception 'CASHIER_REQUIRED' using errcode = '42501';
  end if;
  if requested_id is null or requested_stall is null
    or nullif(p_transaction ->> 'stall_id', '')::uuid is distinct from requested_stall
    or nullif(p_transaction ->> 'receipt_number', '') is null then
    raise exception 'INVALID_TRANSACTION_IDENTITY' using errcode = '22023';
  end if;
  if not public.active_pos_device(nullif(p_transaction ->> 'device_id', '')::uuid) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501';
  end if;

  select * into saved from public.transactions t
  where t.id = requested_id
     or (t.stall_id = requested_stall and t.receipt_number = p_transaction ->> 'receipt_number')
  order by (t.id = requested_id) desc limit 1;
  if found then
    select * into uploaded from private.pos_sale_uploads u where u.transaction_id = saved.id;
    if saved.id <> requested_id or saved.stall_id <> requested_stall
      or saved.receipt_number <> p_transaction ->> 'receipt_number'
      or uploaded.payload is distinct from p_transaction then
      raise exception 'IDEMPOTENCY_CONFLICT' using errcode = '23505';
    end if;
    return jsonb_build_object('status', 'duplicate', 'transaction_id', saved.id,
      'receipt_number', saved.receipt_number, 'inserted_items', 0, 'inserted_ledger', 0);
  end if;

  result := public.push_pos_transaction_device_guarded(p_transaction);
  if result ->> 'status' <> 'accepted' or result ->> 'transaction_id' <> requested_id::text then
    -- Covers a concurrent insert that the earlier lookup could not yet see.
    select * into uploaded from private.pos_sale_uploads u where u.transaction_id = requested_id;
    if result ->> 'status' <> 'duplicate' or uploaded.payload is distinct from p_transaction then
      raise exception 'IDEMPOTENCY_CONFLICT' using errcode = '23505';
    end if;
    return result;
  end if;
  insert into private.pos_sale_uploads(transaction_id, stall_id, payload)
  values (requested_id, requested_stall, p_transaction);
  return result;
end;
$$;
revoke all on function public.push_pos_transaction(jsonb) from public;
grant execute on function public.push_pos_transaction(jsonb) to anon, authenticated;
