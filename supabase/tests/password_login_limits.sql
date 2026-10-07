-- Run only after every migration on a disposable database. Changes roll back.
begin;
set local search_path = public, extensions;

do $$
declare
  test_stall uuid := gen_random_uuid();
  test_user uuid := gen_random_uuid();
  test_email text := 'login-' || gen_random_uuid()::text || '@example.invalid';
  test_code text := 'LOGIN-' || upper(substr(gen_random_uuid()::text, 1, 8));
  attempt integer;
  session_count integer;
begin
  insert into public.stalls(id, name, code)
  values (test_stall, 'Login limit fixture', test_code);
  insert into public.app_users(id, stall_id, email, display_name, role, password_hash)
  values (test_user, test_stall, test_email, 'Cashier fixture', 'cashier',
    crypt('correct-password', gen_salt('bf')));
  perform set_config('request.headers', '{"x-device-id":"test-device"}', true);

  if has_schema_privilege('anon', 'private', 'USAGE')
    or has_table_privilege('anon', 'private.password_login_failures', 'SELECT')
    or has_function_privilege('anon', 'private.password_login_allowed(uuid,text)', 'EXECUTE') then
    raise exception 'Anonymous clients can access login failure internals';
  end if;

  for attempt in 1..4 loop
    if exists (select 1 from public.login_with_password(test_email, 'wrong-password')) then
      raise exception 'Incorrect web password returned a session';
    end if;
  end loop;
  if (select failed_count from private.password_login_failures where user_id = test_user) <> 4 then
    raise exception 'Failed web attempts were not persisted';
  end if;

  if exists (select 1 from public.login_pos_with_password(test_code, test_email, 'wrong-password')) then
    raise exception 'Incorrect POS password returned a session';
  end if;
  if not exists (select 1 from private.password_login_failures
    where user_id = test_user and failed_count = 5 and blocked_until > clock_timestamp()) then
    raise exception 'POS attempt did not block the shared account';
  end if;

  if exists (select 1 from public.login_with_password(test_email, 'correct-password'))
    or exists (select 1 from public.login_pos_with_password(test_code, test_email, 'correct-password')) then
    raise exception 'A blocked account signed in with a correct password';
  end if;
  select count(*) into session_count from public.app_sessions where user_id = test_user;
  if session_count <> 0 then raise exception 'A denied login issued a session'; end if;

  -- Simulate the end of the cool-down without waiting fifteen minutes.
  update private.password_login_failures
  set blocked_until = clock_timestamp() - interval '1 second',
      window_started_at = clock_timestamp() - interval '16 minutes'
  where user_id = test_user;
  if not exists (select 1 from public.login_pos_with_password(test_code, test_email, 'correct-password')) then
    raise exception 'Correct POS password failed after cool-down';
  end if;
  if exists (select 1 from private.password_login_failures where user_id = test_user) then
    raise exception 'Successful login did not clear failure history';
  end if;
  if exists (select 1 from public.login_pos_with_password('WRONG-STALL', test_email, 'correct-password')) then
    raise exception 'Wrong stall code returned a session';
  end if;
  if exists (select 1 from private.password_login_failures where user_id = test_user) then
    raise exception 'Wrong stall code changed the account counter';
  end if;
  if not exists (select 1 from public.login_with_password(test_email, 'correct-password')) then
    raise exception 'Correct web password failed after POS login';
  end if;
end;
$$;
rollback;
