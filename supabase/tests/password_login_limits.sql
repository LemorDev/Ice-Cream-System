-- Run only after every migration on a disposable database. Changes roll back.
begin;
set local search_path = public, extensions;

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
begin
  insert into public.stalls(id, name, code)
  values (test_stall, 'Login limit fixture', test_code);
  insert into public.app_users(id, stall_id, email, employee_id, display_name, role, password_hash) values
    (admin_user, test_stall, test_email, null, 'Admin fixture', 'system_admin', crypt('correct-password', gen_salt('bf'))),
    (test_user, test_stall, null, employee, 'Cashier fixture', 'cashier', crypt('correct-password', gen_salt('bf')));
  perform set_config('request.headers', '{"x-device-id":"test-device"}', true);

  if has_schema_privilege('anon', 'private', 'USAGE')
    or has_table_privilege('anon', 'private.password_login_failures', 'SELECT')
    or has_function_privilege('anon', 'private.password_login_allowed(uuid,text)', 'EXECUTE') then
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
end;
$$;
rollback;
