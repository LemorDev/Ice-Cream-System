-- Disposable database only. This fixture simulates the trusted verifier's
-- successful WebAuthn result to test the database enforcement boundary.
begin;
set local search_path = public, extensions;

do $$
declare
  stall uuid := gen_random_uuid();
  admin_id uuid := gen_random_uuid();
  challenge_id uuid;
  challenge jsonb;
  enrollment_code text;
  admin_token text := 'admin-proof-fixture';
  other_token text := 'admin-other-session-fixture';
begin
  if has_function_privilege('anon', 'public.finish_admin_passkey(text,text,text,bigint,bigint,text)', 'execute')
    or has_function_privilege('authenticated', 'public.begin_admin_passkey(text,text,text)', 'execute')
    or has_function_privilege('anon', 'public.issue_admin_passkey_enrollment(uuid)', 'execute')
    or has_function_privilege('service_role', 'public.enable_admin_passkey_policy()', 'execute') then
    raise exception 'A public caller can bypass passkey verification or enrollment';
  end if;

  insert into public.stalls(id,name,code) values (stall,'Passkey fixture','PASSKEY-FIXTURE');
  insert into public.app_users(id,stall_id,email,display_name,role,password_hash)
  values (admin_id,stall,'passkey@example.invalid','Admin','system_admin','unused');
  insert into public.app_sessions(user_id,token_hash,expires_at) values
    (admin_id,encode(digest(admin_token,'sha256'),'hex'),now()+interval '1 hour'),
    (admin_id,encode(digest(other_token,'sha256'),'hex'),now()+interval '1 hour');
  begin
    perform public.enable_admin_passkey_policy();
    raise exception 'Policy could lock out an unenrolled administrator';
  exception when invalid_parameter_value then null; end;

  enrollment_code := public.issue_admin_passkey_enrollment(admin_id);
  perform set_config('role','service_role',true);
  challenge_id := public.begin_admin_passkey(admin_token,'fixture-enrollment-challenge',enrollment_code);
  if public.consume_admin_passkey_challenge(other_token,challenge_id,true) is not null
    or public.consume_admin_passkey_challenge(admin_token,challenge_id,false) is not null then
    raise exception 'Challenge was usable from another session or ceremony';
  end if;
  challenge := public.consume_admin_passkey_challenge(admin_token,challenge_id,true);
  if challenge->>'challenge' is distinct from 'fixture-enrollment-challenge'
    or public.consume_admin_passkey_challenge(admin_token,challenge_id,true) is not null then
    raise exception 'Challenge did not bind to the verifier or was replayable';
  end if;
  perform public.finish_admin_passkey(admin_token,'fixture-credential','fixture-public-key',null,0,challenge->>'enrollment_hash');
  begin
    perform public.begin_admin_passkey(other_token,'fixture-replay',enrollment_code);
    raise exception 'Enrollment code remained reusable';
  exception when insufficient_privilege then null; end;
  perform set_config('role','none',true);
  perform public.enable_admin_passkey_policy();

  perform set_config('role','anon',true);
  perform set_config('request.headers',jsonb_build_object('x-session-token',other_token)::text,true);
  if public.current_app_user_id() is not null or public.can_manage_stall(stall) is distinct from false then
    raise exception 'A password-only session inherited administrator assurance';
  end if;
  begin
    perform public.reset_stall_transactions(stall);
    raise exception 'Password-only session could reset sales';
  exception when insufficient_privilege then null; end;
  perform set_config('request.headers',jsonb_build_object('x-session-token',admin_token)::text,true);
  if public.current_app_user_id() is distinct from admin_id or public.can_manage_stall(stall) is distinct from true then
    raise exception 'Fresh verification did not authorize the administrator';
  end if;
  perform public.create_managed_stall('Fresh administrator fixture','PASSKEY-ALLOWED');

  perform set_config('role','none',true);
  update public.app_sessions set admin_verified_at=now()-interval '16 minutes'
    where token_hash=encode(digest(admin_token,'sha256'),'hex');
  perform set_config('role','anon',true);
  if public.current_app_user_id() is distinct from admin_id or public.can_manage_stall(stall) is distinct from false then
    raise exception 'Stale assurance did not preserve read access and deny administration';
  end if;
  begin
    perform public.create_device_activation(stall,'Stale administrator fixture');
    raise exception 'Stale assurance could create an activation code';
  exception when insufficient_privilege then null; end;

  perform set_config('role','service_role',true);
  challenge_id := public.begin_admin_passkey(admin_token,'fixture-authentication-challenge',null);
  challenge := public.consume_admin_passkey_challenge(admin_token,challenge_id,false);
  if challenge->>'challenge' is distinct from 'fixture-authentication-challenge' then
    raise exception 'Authentication challenge could not be verified';
  end if;
  perform public.finish_admin_passkey(admin_token,'fixture-credential',null,0,1,null);
  perform set_config('role','anon',true);
  if public.can_manage_stall(stall) is distinct from true then
    raise exception 'Step-up did not restore administrator access';
  end if;
  perform set_config('role','none',true);
  perform public.revoke_admin_passkeys(admin_id);
  if public.current_app_user_id() is not null or public.admin_passkey_context(other_token) is not null then
    raise exception 'Recovery failed to revoke previous sessions';
  end if;
end;
$$;

rollback;
