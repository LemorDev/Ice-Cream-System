-- Run only after every migration on a disposable database. Changes roll back.
begin;
set local search_path = public, extensions;

do $$
begin
  begin
    perform public.create_initial_system_admin('Bootstrap fixture', 'BOOTSTRAP',
      'bootstrap@example.invalid', 'passwordpassword', 'Bootstrap admin');
    raise exception 'The bootstrap accepted a weak password';
  exception when invalid_parameter_value then null;
  end;
  perform public.create_initial_system_admin('Bootstrap fixture', 'BOOTSTRAP',
    'bootstrap@example.invalid', 'A painted bicycle beside the pier', 'Bootstrap admin');
  if not exists (select 1 from public.login_with_password('bootstrap@example.invalid', 'A painted bicycle beside the pier')) then
    raise exception 'The bootstrapped System Administrator could not sign in';
  end if;
end;
$$;

do $$
declare
  test_stall uuid := gen_random_uuid();
  test_user uuid := gen_random_uuid();
  admin_user uuid := gen_random_uuid();
  test_email text := 'login-' || gen_random_uuid()::text || '@example.invalid';
  employee text := 'LOGIN01';
  test_code text := 'LOGIN-' || upper(substr(gen_random_uuid()::text, 1, 8));
  attempt integer;
  session_count integer;
  admin_token text;
  owner_id uuid;
  owner_username text := 'fixture-owner-' || gen_random_uuid()::text;
  long_password text := repeat('é', 40) || ' orchid trail moonbeam';
  new_cashier_password text := 'Granite clouds over the quiet quay';
  rejected_password text;
  original_hash text;
begin
  insert into public.stalls(id, name, code)
  values (test_stall, 'Login limit fixture', test_code);
  insert into public.app_users(id, stall_id, email, employee_id, display_name, role, password_hash) values
    (admin_user, test_stall, test_email, null, 'Admin fixture', 'system_admin', crypt('correct-password', gen_salt('bf'))),
    (test_user, test_stall, null, employee, 'Cashier fixture', 'cashier', crypt('correct-password', gen_salt('bf')));
  perform set_config('request.headers', '{"x-device-id":"test-device"}', true);

  if has_schema_privilege('anon', 'private', 'USAGE')
    or has_table_privilege('anon', 'private.password_login_failures', 'SELECT')
    or has_table_privilege('anon', 'private.password_login_network_limits', 'SELECT')
    or has_function_privilege('anon', 'private.password_login_allowed(uuid,text)', 'EXECUTE')
    or has_function_privilege('anon', 'private.password_login_network_allowed()', 'EXECUTE')
    or has_function_privilege('anon', 'private.hash_new_password(text,text[])', 'EXECUTE')
    or has_function_privilege('anon', 'public.login_pos_with_password_unchecked(text,text,text)', 'EXECUTE') then
    raise exception 'Anonymous clients can access login failure internals';
  end if;

  for attempt in 1..5 loop
    if exists (select 1 from public.login_with_password(test_email, 'wrong-password'))
      or exists (select 1 from public.login_pos_with_password(test_code, employee, 'wrong-password')) then
      raise exception 'Incorrect password returned a session';
    end if;
  end loop;
  if (select count(*) from private.password_login_failures where user_id in (test_user,admin_user)
      and failed_count=5 and blocked_until>clock_timestamp())<>2 then
    raise exception 'Web and POS accounts were not throttled independently';
  end if;

  if exists (select 1 from public.login_with_password(test_email, 'correct-password'))
    or exists (select 1 from public.login_pos_with_password(test_code, employee, 'correct-password')) then
    raise exception 'A blocked account signed in with a correct password';
  end if;
  select count(*) into session_count from public.app_sessions where user_id in (test_user,admin_user);
  if session_count <> 0 then raise exception 'A denied login issued a session'; end if;

  -- Simulate the end of the cool-down without waiting fifteen minutes.
  update private.password_login_failures
  set blocked_until = clock_timestamp() - interval '1 second',
      window_started_at = clock_timestamp() - interval '16 minutes'
  where user_id in (test_user,admin_user);
  perform public.login_with_password(test_email, 'wrong-password');
  if (select failed_count from private.password_login_failures where user_id = admin_user) is distinct from 1 then
    raise exception 'An expired cooldown did not start a fresh failure window';
  end if;
  if not exists (select 1 from public.login_pos_with_password(lower(test_code), lower(employee), 'correct-password'))
    or not exists (select 1 from public.login_with_password(upper(test_email), 'correct-password')) then
    raise exception 'Correct identifiers failed after cool-down';
  end if;
  if exists (select 1 from private.password_login_failures where user_id in (test_user,admin_user)) then
    raise exception 'Successful login did not clear failure history';
  end if;
  if exists (select 1 from public.login_pos_with_password('WRONG-STALL', employee, 'correct-password'))
    or exists (select 1 from public.login_pos_with_password(test_code, test_email, 'correct-password'))
    or exists (select 1 from public.login_with_password(employee, 'correct-password')) then
    raise exception 'A wrong stall or wrong login channel returned a session';
  end if;
  if exists (select 1 from private.password_login_failures where user_id in (test_user,admin_user)) then
    raise exception 'A disallowed login channel changed the account counters';
  end if;

  perform set_config('request.headers', '{"x-device-id":"test-device","cf-connecting-ip":"2001:db8:1::a"}', true);
  for attempt in 1..15 loop
    if exists (select 1 from public.login_with_password('missing-owner', 'wrong-password'))
      or exists (select 1 from public.login_pos_with_password(test_code, 'MISSING-CASHIER', 'wrong-password')) then
      raise exception 'Unknown credentials returned a session';
    end if;
  end loop;
  if (select attempt_count from private.password_login_network_limits where source_network = '2001:db8:1::/64'::inet)
    is distinct from 30 then
    raise exception 'Web and POS unknown identifiers did not share the source limit';
  end if;
  perform set_config('request.headers', '{"x-device-id":"test-device","cf-connecting-ip":"2001:db8:1::b","x-forwarded-for":"192.0.2.20"}', true);
  if exists (select 1 from public.login_pos_with_password(test_code, employee, 'correct-password'))
    or current_setting('response.status', true) is distinct from '429'
    or (current_setting('response.headers', true)::jsonb -> 0 ->> 'Retry-After')::integer not between 1 and 60 then
    raise exception 'A throttled network bypassed its limit or failed to receive retry guidance';
  end if;

  perform set_config('request.headers', '{"cf-connecting-ip":"192.0.2.21"}', true);
  select session_token into strict admin_token from public.login_with_password(test_email, 'correct-password');
  update private.password_login_network_limits set expires_at = clock_timestamp() - interval '1 second'
  where source_network = '2001:db8:1::/64'::inet;
  perform set_config('request.headers', '{"x-device-id":"test-device","cf-connecting-ip":"2001:db8:1::b"}', true);
  if not exists (select 1 from public.login_pos_with_password(test_code, employee, 'correct-password')) then
    raise exception 'The source limit did not expire';
  end if;

  perform set_config('request.headers', jsonb_build_object('x-device-id', 'test-device', 'x-session-token', admin_token)::text, true);
  foreach rejected_password in array array[null, '', 'too short', repeat('x', 129), '123456789012345',
    'PasswordP@ssw0rd1!', upper(owner_username)]::text[] loop
    begin
      perform public.save_managed_user(test_stall, owner_username, null, 'New Owner', 'owner', rejected_password);
      raise exception 'A weak new password was accepted';
    exception when invalid_parameter_value then null;
    end;
  end loop;

  owner_id := (public.save_managed_user(test_stall, owner_username, null, 'New Owner', 'owner', long_password) ->> 'id')::uuid;
  if exists (select 1 from public.login_with_password(owner_username, repeat('é', 40) || ' altered suffix'))
    or not exists (select 1 from public.login_with_password(owner_username, normalize(long_password, NFD))) then
    raise exception 'Long Unicode passwords were truncated or failed NFC verification';
  end if;
  select password_hash into strict original_hash from public.app_users where id = owner_id;
  perform public.save_managed_user(test_stall, owner_username, null, 'Updated Owner', 'owner', null, owner_id);
  if (select password_hash from public.app_users where id = owner_id) is distinct from original_hash then
    raise exception 'Editing a profile without a password replaced its hash';
  end if;

  begin
    perform public.save_managed_user(test_stall, null, employee, 'Cashier fixture', 'cashier', 'passwordpassword', test_user);
    raise exception 'A weak Cashier password reset was accepted';
  exception when invalid_parameter_value then null;
  end;
  perform public.save_managed_user(test_stall, null, employee, 'Cashier fixture', 'cashier', new_cashier_password, test_user);
  if exists (select 1 from public.app_sessions where user_id = test_user and revoked_at is null) then
    raise exception 'The password reset left a previous POS session active';
  end if;
  if exists (select 1 from public.login_pos_with_password(test_code, employee, 'correct-password'))
    or not exists (select 1 from public.login_pos_with_password(test_code, employee, new_cashier_password)) then
    raise exception 'Cashier password reset did not change POS sign-in';
  end if;
end;
$$;
rollback;
