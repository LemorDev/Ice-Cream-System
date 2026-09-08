-- Run against a disposable local database after applying every migration.
-- Fixtures and mutations are rolled back; no application credentials are needed.
begin;
set local search_path = public, extensions;

do $$
declare
  stall_a uuid := gen_random_uuid();
  stall_b uuid := gen_random_uuid();
  stall_c uuid := gen_random_uuid();
  admin_id uuid := gen_random_uuid();
  owner_id uuid := gen_random_uuid();
  cashier_id uuid := gen_random_uuid();
  product_b uuid := gen_random_uuid();
  admin_token text := encode(gen_random_bytes(32), 'hex');
  owner_token text := encode(gen_random_bytes(32), 'hex');
  cashier_token text := encode(gen_random_bytes(32), 'hex');
  activation jsonb;
  replacement_activation jsonb;
  activation_result jsonb;
  day_id uuid := gen_random_uuid();
  day_result jsonb;
  opened_at timestamptz := now() - interval '30 minutes';
begin
  insert into public.stalls (id, name, code) values
    (stall_a, 'RBAC stall A', 'RBAC-' || substr(stall_a::text, 1, 8)),
    (stall_b, 'RBAC stall B', 'RBAC-' || substr(stall_b::text, 1, 8)),
    (stall_c, 'RBAC stall C', 'RBAC-' || substr(stall_c::text, 1, 8));
  insert into public.app_users (id, stall_id, email, display_name, role, password_hash) values
    (admin_id, stall_a, admin_id::text || '@example.invalid', 'System admin fixture', 'system_admin', 'unused'),
    (owner_id, stall_a, owner_id::text || '@example.invalid', 'Owner fixture', 'owner', 'unused'),
    (cashier_id, stall_a, cashier_id::text || '@example.invalid', 'Cashier fixture', 'cashier', 'unused');
  insert into public.owner_stall_access (user_id, stall_id) values
    (owner_id, stall_a),
    (owner_id, stall_b);
  insert into public.app_sessions (user_id, token_hash, expires_at) values
    (admin_id, encode(digest(admin_token, 'sha256'), 'hex'), now() + interval '1 hour'),
    (owner_id, encode(digest(owner_token, 'sha256'), 'hex'), now() + interval '1 hour'),
    (cashier_id, encode(digest(cashier_token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.products (id, stall_id, sku, name, unit, low_stock_threshold)
  values (product_b, stall_b, 'RBAC-PRODUCT-B', 'Assigned stall product', 'piece', 2);
  insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type)
  values (stall_b, product_b, 5, 'opening_balance');

  perform set_config('request.headers', jsonb_build_object('x-session-token', owner_token)::text, true);
  if not public.is_owner() or public.is_system_admin() then
    raise exception 'Owner role helpers returned the wrong result';
  end if;
  if not public.can_manage_stall(stall_a) or not public.can_manage_stall(stall_b)
    or public.can_manage_stall(stall_c) then
    raise exception 'Owner stall assignments were not enforced';
  end if;
  if (select count(*) from public.get_my_stalls()) <> 2 then
    raise exception 'Owner did not receive exactly the assigned stalls';
  end if;
  if public.get_stock_on_hand(stall_b, product_b) <> 5
    or public.get_low_stock_threshold(product_b) <> 2 then
    raise exception 'Owner could not read inventory for a secondary assigned stall';
  end if;

  perform public.save_managed_user(
    stall_a, 'new-cashier-' || substr(gen_random_uuid()::text, 1, 8) || '@example.invalid',
    'New cashier', 'cashier', 'cashier-password', null, true
  );
  begin
    perform public.save_managed_user(
      stall_a, 'owner-' || substr(gen_random_uuid()::text, 1, 8) || '@example.invalid',
      'Unauthorized owner', 'owner', 'owner-password', null, true
    );
    raise exception 'Owner was allowed to create another owner';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.create_managed_stall('Unauthorized stall', 'NOPE-' || substr(gen_random_uuid()::text, 1, 8));
    raise exception 'Owner was allowed to create a stall';
  exception when insufficient_privilege then null;
  end;

  activation := public.create_device_activation(stall_a, 'RBAC test POS');
  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token)::text, true);
  activation_result := public.activate_pos_device(activation ->> 'activation_code', 'rbac-test-hardware');
  if activation_result ->> 'stall_id' <> stall_a::text then
    raise exception 'Cashier activation returned the wrong stall';
  end if;

  perform set_config('request.headers', jsonb_build_object(
    'x-session-token', cashier_token, 'x-device-id', 'rbac-test-hardware'
  )::text, true);
  day_result := public.push_business_day(jsonb_build_object(
    'id', day_id,
    'stall_id', stall_a,
    'device_id', activation_result ->> 'device_id',
    'business_date', (opened_at at time zone 'Asia/Manila')::date,
    'opened_at', opened_at,
    'opening_notes', 'Ready'
  ));
  if day_result ->> 'status' <> 'accepted' then
    raise exception 'Opening the operating day was not accepted';
  end if;
  day_result := public.push_business_day(jsonb_build_object(
    'id', day_id,
    'stall_id', stall_a,
    'device_id', activation_result ->> 'device_id',
    'business_date', (opened_at at time zone 'Asia/Manila')::date,
    'opened_at', opened_at,
    'closed_at', now(),
    'closing_cash_total', 1250,
    'closing_notes', 'Counted'
  ));
  if day_result ->> 'status' <> 'accepted'
    or not exists (select 1 from public.business_days where id = day_id and closed_at is not null) then
    raise exception 'Closing the operating day was not recorded';
  end if;

  perform set_config('request.headers', jsonb_build_object('x-session-token', owner_token)::text, true);
  replacement_activation := public.create_device_activation(stall_a, 'Replacement POS');
  if not exists (select 1 from public.devices where id = (activation_result ->> 'device_id')::uuid and is_active) then
    raise exception 'Creating a replacement code disabled the working POS too early';
  end if;
  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token)::text, true);
  perform public.activate_pos_device(replacement_activation ->> 'activation_code', 'rbac-replacement-hardware');
  if exists (select 1 from public.devices where id = (activation_result ->> 'device_id')::uuid and is_active) then
    raise exception 'Redeeming a replacement code did not deactivate the previous POS';
  end if;

  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  if not public.is_system_admin() or not public.can_manage_stall(stall_c) then
    raise exception 'System administrator does not have global stall access';
  end if;
  if (select cardinality(stall_ids) from public.list_managed_users(stall_b) where id = owner_id) <> 2 then
    raise exception 'Owner directory did not return every stall assignment';
  end if;
  perform public.set_owner_stalls(owner_id, array[stall_c]);
  perform set_config('request.headers', jsonb_build_object('x-session-token', owner_token)::text, true);
  if public.can_manage_stall(stall_a) or not public.can_manage_stall(stall_c)
    or (select count(*) from public.get_my_stalls()) <> 1 then
    raise exception 'Updated owner stall assignments were not enforced';
  end if;
end;
$$;

rollback;
