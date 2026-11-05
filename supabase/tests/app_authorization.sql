-- Run against a disposable database after every migration. All fixtures roll back.
begin;
set local search_path = public, extensions;

do $$
declare
  stall_a uuid := gen_random_uuid();
  stall_b uuid := gen_random_uuid();
  admin_id uuid := gen_random_uuid();
  owner_id uuid := gen_random_uuid();
  cashier_id uuid := gen_random_uuid();
  disabled_id uuid := gen_random_uuid();
  device_id uuid := gen_random_uuid();
  day_id uuid := gen_random_uuid();
  product_id uuid := gen_random_uuid();
  incident_id uuid := gen_random_uuid();
  caller record;
  command text;
  admin_calls text[];
begin
  insert into public.stalls (id, name, code) values
    (stall_a, 'Authorization stall A', 'AUTH-A'),
    (stall_b, 'Authorization stall B', 'AUTH-B');
  insert into public.app_users (id, stall_id, email, display_name, role, password_hash, is_active) values
    (admin_id, stall_a, 'auth-admin@example.invalid', 'Admin', 'system_admin', 'unused', true),
    (owner_id, stall_a, 'auth-owner@example.invalid', 'Owner', 'owner', 'unused', true),
    (cashier_id, stall_a, 'auth-cashier@example.invalid', 'Cashier', 'cashier', 'unused', true),
    (disabled_id, stall_a, 'auth-disabled@example.invalid', 'Disabled', 'system_admin', 'unused', false);
  insert into public.owner_stall_access (user_id, stall_id) values (owner_id, stall_a);
  insert into public.app_sessions (user_id, token_hash, expires_at, revoked_at)
  select user_id, encode(digest(token, 'sha256'), 'hex'), expires_at, revoked_at
  from (values
    (admin_id, 'fixture-admin', now() + interval '1 hour', null::timestamptz),
    (admin_id, 'true', now() + interval '1 hour', null),
    (owner_id, 'fixture-owner', now() + interval '1 hour', null),
    (cashier_id, 'fixture-cashier', now() + interval '1 hour', null),
    (admin_id, 'fixture-expired', now() - interval '1 hour', null),
    (admin_id, 'fixture-revoked', now() + interval '1 hour', now()),
    (disabled_id, 'fixture-disabled', now() + interval '1 hour', null)
  ) as sessions(user_id, token, expires_at, revoked_at);
  insert into public.devices (id, stall_id, device_name, activation_code_hash, hardware_id)
  values (device_id, stall_a, 'Authorization POS', 'fixture-device-code-hash', 'true');
  insert into public.business_days (id, stall_id, device_id, cashier_id, business_date, opened_at)
  values (day_id, stall_a, device_id, cashier_id, current_date, now());
  insert into public.products (id, stall_id, sku, name, unit, product_type, base_unit)
  values (product_id, stall_a, 'AUTH-PRODUCT', 'Authorization product', 'piece', 'raw', 'piece');
  insert into public.pos_recovery_incidents (id, stall_id, old_device_id, reason, status)
  values (incident_id, stall_a, device_id, 'Fixture recovery', 'activated');

  admin_calls := array[
    'select public.create_managed_stall(''Unauthorized fixture'', ''AUTH-DENIED'')',
    format('select public.save_managed_user(%L, %L, null, %L, %L, %L)',
      stall_a, 'fixture-denied', 'Denied user', 'owner', 'Fixture credential with enough length'),
    format('select public.set_owner_stalls(%L, array[%L::uuid])', owner_id, stall_b),
    format('select public.get_pos_device_status(%L)', stall_a),
    format('select public.create_device_activation(%L, %L)', stall_a, 'Denied device'),
    format('select public.authorize_pos_recovery(%L, %L)', stall_a, 'Denied recovery request'),
    format('select public.review_pos_recovery(%L, %L)', incident_id, 'Denied recovery review'),
    format('select public.revoke_pending_pos_activation(%L)', stall_a),
    format('select public.update_managed_stall(%L, %L)', stall_a, 'Denied stall update'),
    format('select public.save_product_with_recipe(%L)', jsonb_build_object('stall_id', stall_a)),
    format('select public.set_stock_on_hand(%L, %L, 1, %L)', stall_a, product_id, 'Denied adjustment'),
    format('select public.set_powder_sealed_packs(%L, 1)', product_id),
    format('select public.reset_stall_transactions(%L)', stall_a),
    format('select public.reset_stall_stock(%L)', stall_a)
  ];

  for caller in select * from (values
    ('anon', '{"x-device-id":"true"}', false, false),
    ('anon', '', false, false),
    ('authenticated', '{}', false, false),
    ('authenticated', '{"x-session-token":', false, false),
    ('anon', '[{"x-session-token":"fixture-admin"}]', false, false),
    ('anon', '{"x-session-token":true,"x-device-id":{}}', false, false),
    ('anon', '{"x-session-token":"fixture-invalid"}', false, false),
    ('anon', '{"x-session-token":"fixture-expired"}', false, false),
    ('anon', '{"x-session-token":"fixture-revoked"}', false, false),
    ('anon', '{"x-session-token":"fixture-disabled"}', false, false),
    ('anon', '{"x-session-token":"fixture-owner"}', true, false),
    ('anon', '{"x-session-token":"fixture-cashier","x-device-id":"true"}', false, true)
  ) as cases(gateway_role, headers, app_owner, app_cashier) loop
    perform set_config('role', caller.gateway_role, true);
    perform set_config('request.headers', caller.headers, true);
    if public.is_system_admin() is distinct from false
      or public.is_owner() is distinct from caller.app_owner
      or public.can_manage_stall(stall_a) is distinct from false
      or public.can_access_stall(stall_a) is distinct from (caller.app_owner or caller.app_cashier)
      or public.can_access_stall(stall_b) is distinct from false
      or public.can_access_stall(null) is distinct from false
      or public.active_pos_device(device_id) is distinct from caller.app_cashier then
      raise exception 'Unexpected authorization predicates for role matrix case %', caller;
    end if;

    foreach command in array admin_calls loop
      begin
        execute command;
        raise exception 'Privileged RPC did not deny access: %', command;
      exception when insufficient_privilege then
        if sqlerrm <> 'FORBIDDEN' then raise; end if;
      end;
    end loop;

    if (select count(*) from public.stalls where id in (stall_a, stall_b))
        <> (case when caller.app_owner then 1 else 0 end) then
      raise exception 'Direct stall reads exceeded the caller scope';
    end if;
    update public.products set name = 'Denied direct update' where id = product_id;
    if found then raise exception 'Direct product update bypassed RLS'; end if;

    if not caller.app_cashier then
      begin
        perform public.prepare_pos_replacement(device_id);
        raise exception 'Non-Cashier was allowed to prepare POS replacement';
      exception when insufficient_privilege then
        if sqlerrm <> 'ACTIVE_DEVICE_REQUIRED' then raise; end if;
      end;
    end if;

    if caller.gateway_role = 'authenticated' then
      begin
        perform public.admin_close_open_business_day(day_id, 0, 'Denied operating-day close');
        raise exception 'Gateway authentication alone allowed an administrator close';
      exception when insufficient_privilege then
        if sqlerrm <> 'FORBIDDEN' then raise; end if;
      end;
    end if;
  end loop;

  perform set_config('role', 'anon', true);
  perform set_config('request.headers', '{"x-session-token":"fixture-cashier","x-device-id":true}', true);
  if public.current_app_role() is distinct from 'cashier'::public.app_role
    or public.active_pos_device(device_id) is distinct from false then
    raise exception 'A non-string device header authorized a Cashier device';
  end if;
  perform set_config('request.headers', '{"x-session-token":"fixture-admin"}', true);
  if public.can_manage_stall(stall_a) is distinct from true
    or public.can_manage_stall(stall_b) is distinct from true
    or public.can_manage_stall(null) is distinct from false
    or public.active_pos_device(device_id) is distinct from false
    or public.get_pos_device_status(stall_a) ->> 'active_device_id' is distinct from device_id::text then
    raise exception 'System Administrator custom-session access regressed';
  end if;
  perform public.create_managed_stall('Authorized fixture', 'AUTH-ALLOWED');
end;
$$;

rollback;
