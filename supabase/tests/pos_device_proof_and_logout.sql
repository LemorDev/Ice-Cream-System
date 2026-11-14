-- Disposable database only. Permit issuance simulates the Edge service role.
begin;
set local search_path = public, extensions;

do $$
declare
  stall uuid := gen_random_uuid();
  cashier uuid := gen_random_uuid();
  device uuid := gen_random_uuid();
  token text := encode(gen_random_bytes(32), 'hex');
  other_token text := encode(gen_random_bytes(32), 'hex');
  token_hash text := encode(digest(token, 'sha256'), 'hex');
  hardware text := '000000012345abcd';
  key_a text := repeat('A', 124);
  key_b text := repeat('B', 124);
  nonce uuid := gen_random_uuid();
  code text;
  permit text;
  activated jsonb;
  signed_in record;
  fingerprint_secret text := encode(gen_random_bytes(32), 'hex');
  fingerprint_session text;
  fresh_stall uuid := gen_random_uuid();
  fresh_cashier uuid := gen_random_uuid();
  fresh_device uuid := gen_random_uuid();
  fresh_token text := encode(gen_random_bytes(32), 'hex');
  fresh_hardware text := '000000012345abce';
  renewal_token text := encode(gen_random_bytes(32), 'hex');
  renewal_hash text := encode(digest(renewal_token, 'sha256'), 'hex');
  renewal_headers jsonb := jsonb_build_object('x-session-token', renewal_token, 'x-device-id', hardware);
  headers jsonb := jsonb_build_object('x-session-token', token, 'x-device-id', hardware);
begin
  if has_function_privilege('anon', 'public.issue_pos_request_permit(text,uuid,text,text,text,text,text)', 'EXECUTE')
    or has_function_privilege('authenticated', 'public.issue_pos_device_key_enrollment(uuid)', 'EXECUTE')
    or has_function_privilege('anon', 'private.activate_pos_device(text,text)', 'EXECUTE') then
    raise exception 'A gateway caller can bypass proof enrollment';
  end if;

  insert into public.stalls(id,name,code) values (stall,'Proof fixture','PROOF-'||upper(substr(stall::text,1,8)));
  insert into public.app_users(id,stall_id,email,employee_id,display_name,role,password_hash)
  values (cashier,stall,cashier::text||'@example.invalid','TEST-01','Proof Cashier','cashier',
    private.hash_new_password('Fixture password clouds and shells', array['TEST-01']));
  insert into public.devices(id,stall_id,device_name,activation_code_hash,hardware_id)
  values (device,stall,'Existing POS','used',hardware);
  insert into public.app_sessions(user_id,token_hash,expires_at) values
    (cashier,token_hash,now()+interval '1 hour'), (cashier,encode(digest(other_token,'sha256'),'hex'),now()+interval '1 hour');

  perform set_config('role', 'anon', true);
  perform set_config('request.headers', headers::text, true);
  if public.active_pos_device(device) is distinct from true then raise exception 'Staged legacy access regressed'; end if;
  perform set_config('role', 'service_role', true);
  code := public.issue_pos_device_key_enrollment(device);
  permit := public.issue_pos_request_permit(hardware, nonce, key_a, token_hash, 'POST', '/rpc/activate_pos_device', null);
  if permit is null or public.issue_pos_request_permit(hardware, nonce, key_a, token_hash, 'POST', '/rpc/activate_pos_device', null) is not null then
    raise exception 'A signed nonce was accepted more than once';
  end if;
  perform set_config('role', 'anon', true);
  perform set_config('request.method', 'POST', true);
  perform set_config('request.path', '/rpc/activate_pos_device', true);
  perform set_config('request.headers', (headers || jsonb_build_object('x-pos-permit',permit))::text, true);
  activated := public.activate_pos_device(code, hardware);
  if activated->>'device_id' is distinct from device::text then raise exception 'Enrollment replaced the device'; end if;

  perform set_config('request.headers', headers::text, true);
  if public.current_app_user_id() is not null or public.active_pos_device(device) is distinct from false then
    raise exception 'A copied ID and bearer reached an enrolled stall';
  end if;
  begin
    perform public.activate_pos_device(code, hardware);
    raise exception 'Direct activation bypassed device proof';
  exception when insufficient_privilege then null;
  end;

  perform set_config('role', 'service_role', true);
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, token_hash, 'POST', '/rpc/get_pos_catalog_revision', null) is not null then
    raise exception 'The verifier admitted an unregistered key for a protected operation';
  end if;
  permit := public.issue_pos_request_permit(hardware, gen_random_uuid(), key_a, token_hash, 'POST', '/rpc/get_pos_catalog_revision', null);
  perform set_config('role', 'anon', true);
  perform set_config('request.headers', (headers || jsonb_build_object('x-pos-permit',permit))::text, true);
  if public.current_app_user_id() is not null then raise exception 'A permit was accepted on a different route'; end if;
  perform set_config('request.path', '/rpc/get_pos_catalog_revision', true);
  if public.active_pos_device(device) is distinct from true then raise exception 'A matching key and session were rejected'; end if;
  perform set_config('request.headers', (headers || jsonb_build_object('x-pos-permit',permit,'x-session-token',other_token))::text, true);
  if public.current_app_user_id() is not null then raise exception 'A permit authenticated a different bearer'; end if;

  perform set_config('role', 'service_role', true);
  code := public.issue_pos_device_key_enrollment(device);
  permit := public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, encode(digest(other_token,'sha256'),'hex'),
    'POST', '/rpc/activate_pos_device', null);
  perform set_config('role', 'anon', true);
  perform set_config('request.path', '/rpc/activate_pos_device', true);
  perform set_config('request.headers', (headers || jsonb_build_object('x-pos-permit',permit,'x-session-token',other_token))::text, true);
  perform public.activate_pos_device(code, hardware);
  perform set_config('role', 'service_role', true);
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_a, token_hash, 'POST', '/rpc/get_pos_catalog_revision', null) is not null then
    raise exception 'The previous key still works after rotation';
  end if;

  perform set_config('role', 'anon', true);
  perform set_config('request.path', '/rpc/login_pos_with_password', true);
  perform set_config('request.headers', jsonb_build_object('x-device-id', hardware)::text, true);
  select * into signed_in from public.login_pos_with_password('PROOF-'||substr(stall::text,1,8), 'TEST-01', 'Fixture password clouds and shells');
  if signed_in.session_token is null then raise exception 'Provisional password sign-in failed'; end if;
  if signed_in.is_activated is distinct from false then
    raise exception 'A copied Android ID restored an enrolled registration at login';
  end if;
  perform set_config('role', 'service_role', true);
  permit := public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, null, 'POST', '/rpc/login_pos_with_password', null);
  perform set_config('role', 'anon', true);
  perform set_config('request.headers', jsonb_build_object('x-device-id', hardware,'x-pos-permit',permit)::text, true);
  select * into signed_in from public.login_pos_with_password('PROOF-'||substr(stall::text,1,8), 'TEST-01', 'Fixture password clouds and shells');
  if signed_in.device_id is distinct from device or signed_in.is_activated is distinct from true then
    raise exception 'Signed password login did not restore the enrolled device';
  end if;

  perform set_config('role', 'none', true);
  insert into public.app_sessions(user_id, token_hash, expires_at, pos_device_id, pos_key_hash)
    values (cashier, renewal_hash, now() - interval '1 hour', device, encode(digest(key_b, 'sha256'), 'hex'));
  perform set_config('role', 'service_role', true);
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, renewal_hash,
      'POST', '/rpc/get_pos_catalog_revision', null) is not null then
    raise exception 'An expired bearer acquired an ordinary sync permit';
  end if;
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_a, renewal_hash,
      'POST', '/rpc/renew_pos_session', null) is not null
    or public.issue_pos_request_permit(fresh_hardware, gen_random_uuid(), key_b, renewal_hash,
      'POST', '/rpc/renew_pos_session', null) is not null
    or public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, null,
      'POST', '/rpc/renew_pos_session', null) is not null then
    raise exception 'Renewal admitted a missing bearer, wrong device, or prior key';
  end if;
  permit := public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, renewal_hash,
    'POST', '/rpc/renew_pos_session', null);
  if permit is null then raise exception 'Renewal denied the registered device'; end if;
  perform set_config('role', 'anon', true);
  perform set_config('request.path', '/rpc/renew_pos_session', true);
  perform set_config('request.headers', renewal_headers::text, true);
  begin
    perform public.renew_pos_session();
    raise exception 'A direct caller renewed a copied bearer';
  exception when insufficient_privilege then null;
  end;
  perform set_config('request.headers', (renewal_headers || jsonb_build_object('x-pos-permit', permit))::text, true);
  if public.renew_pos_session() is distinct from now() + interval '12 hours' then
    raise exception 'The registered device did not receive a renewed session';
  end if;
  perform set_config('role', 'service_role', true);
  permit := public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, renewal_hash,
    'POST', '/rpc/get_pos_catalog_revision', null);
  perform set_config('role', 'anon', true);
  perform set_config('request.path', '/rpc/get_pos_catalog_revision', true);
  perform set_config('request.headers', (renewal_headers || jsonb_build_object('x-pos-permit', permit))::text, true);
  if public.active_pos_device(device) is distinct from true then
    raise exception 'Ordinary sync failed after renewal';
  end if;

  perform set_config('role', 'none', true);
  update public.app_sessions s set expires_at = now() - interval '31 days' where s.token_hash = renewal_hash;
  perform set_config('role', 'service_role', true);
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, renewal_hash,
      'POST', '/rpc/renew_pos_session', null) is not null then
    raise exception 'Renewal accepted a bearer past its grace period';
  end if;
  perform set_config('role', 'none', true);
  update public.app_sessions s set expires_at = now() - interval '1 hour' where s.token_hash = renewal_hash;
  update public.devices set is_active = false where id = device;
  perform set_config('role', 'service_role', true);
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, renewal_hash,
      'POST', '/rpc/renew_pos_session', null) is not null then
    raise exception 'Renewal accepted a disabled device';
  end if;
  perform set_config('role', 'none', true);
  update public.devices set is_active = true where id = device;
  perform set_config('role', 'service_role', true);
  permit := public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, renewal_hash,
    'POST', '/rpc/renew_pos_session', null);
  perform set_config('role', 'anon', true);
  perform set_config('request.path', '/rpc/renew_pos_session', true);
  perform set_config('request.headers', (renewal_headers || jsonb_build_object('x-pos-permit', permit))::text, true);
  perform public.revoke_app_session();
  begin
    perform public.renew_pos_session();
    raise exception 'A permit obtained before logout revived the revoked bearer';
  exception when insufficient_privilege then null;
  end;
  perform set_config('role', 'service_role', true);
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, renewal_hash,
      'POST', '/rpc/renew_pos_session', null) is not null then
    raise exception 'Logout did not revoke an expired renewable bearer';
  end if;
  perform set_config('role', 'anon', true);
  perform set_config('request.headers', headers::text, true);
  perform public.revoke_app_session();
  perform public.revoke_app_session();
  perform set_config('role', 'service_role', true);
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, token_hash, 'POST', '/rpc/get_pos_catalog_revision', null) is not null then
    raise exception 'Logout left the bearer usable';
  end if;
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, encode(digest(other_token,'sha256'),'hex'),
    'POST', '/rpc/get_pos_catalog_revision', null) is null then
    raise exception 'Logout revoked a different session';
  end if;

  fingerprint_session := signed_in.session_token;
  permit := public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b,
    encode(digest(fingerprint_session, 'sha256'), 'hex'), 'POST', '/rpc/set_pos_fingerprint_credential', null);
  perform set_config('role', 'anon', true);
  perform set_config('request.path', '/rpc/set_pos_fingerprint_credential', true);
  perform set_config('request.headers', (headers || jsonb_build_object('x-session-token', fingerprint_session))::text, true);
  begin
    perform public.set_pos_fingerprint_credential(fingerprint_secret);
    raise exception 'A copied bearer enrolled fingerprint sign-in';
  exception when insufficient_privilege then null;
  end;
  perform set_config('request.headers', (headers || jsonb_build_object(
    'x-session-token', fingerprint_session, 'x-pos-permit', permit))::text, true);
  if not public.set_pos_fingerprint_credential(fingerprint_secret) then
    raise exception 'Fingerprint sign-in enrollment failed';
  end if;
  perform public.revoke_app_session();
  perform set_config('request.path', '/rpc/login_pos_with_fingerprint', true);
  perform set_config('request.headers', jsonb_build_object('x-device-id', hardware)::text, true);
  if exists (select 1 from public.login_pos_with_fingerprint(fingerprint_secret)) then
    raise exception 'A fingerprint credential worked without signed device proof';
  end if;

  perform set_config('role', 'service_role', true);
  if public.issue_pos_request_permit(hardware, gen_random_uuid(), key_a, null,
      'POST', '/rpc/login_pos_with_fingerprint', null) is not null then
    raise exception 'An unregistered key obtained a fingerprint sign-in permit';
  end if;
  permit := public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b, null,
    'POST', '/rpc/login_pos_with_fingerprint', null);
  perform set_config('role', 'anon', true);
  perform set_config('request.headers', jsonb_build_object('x-device-id', hardware, 'x-pos-permit', permit)::text, true);
  select * into signed_in from public.login_pos_with_fingerprint(fingerprint_secret);
  if signed_in.session_token is null or signed_in.session_token = fingerprint_session
    or signed_in.user_id is distinct from cashier or signed_in.device_id is distinct from device
    or signed_in.is_activated is distinct from true then
    raise exception 'Fingerprint sign-in did not create a fresh device-bound cashier session after logout';
  end if;

  perform set_config('role', 'service_role', true);
  permit := public.issue_pos_request_permit(hardware, gen_random_uuid(), key_b,
    encode(digest(signed_in.session_token, 'sha256'), 'hex'), 'POST', '/rpc/set_pos_fingerprint_credential', null);
  perform set_config('role', 'anon', true);
  perform set_config('request.path', '/rpc/set_pos_fingerprint_credential', true);
  perform set_config('request.headers', (headers || jsonb_build_object(
    'x-session-token', signed_in.session_token, 'x-pos-permit', permit))::text, true);
  if not public.set_pos_fingerprint_credential(null) then
    raise exception 'Fingerprint opt-out failed';
  end if;
  perform set_config('role', 'none', true);
  if exists (select 1 from private.pos_fingerprint_credentials where device_id = device) then
    raise exception 'Opt-out left a server credential';
  end if;
  perform set_config('role', 'anon', true);
  perform public.set_pos_fingerprint_credential(fingerprint_secret);
  perform set_config('role', 'none', true);
  update public.app_users set password_hash = 'fixture-changed' where id = cashier;
  if exists (select 1 from private.pos_fingerprint_credentials where device_id = device) then
    raise exception 'Password change left a usable fingerprint credential';
  end if;

  perform set_config('role', 'none', true);
  update private.pos_device_proof_policy set required=true;
  insert into public.stalls(id,name,code) values (fresh_stall,'New POS fixture','NEW-'||upper(substr(fresh_stall::text,1,8)));
  insert into public.app_users(id,stall_id,email,employee_id,display_name,role,password_hash)
  values (fresh_cashier,fresh_stall,fresh_cashier::text||'@example.invalid','TEST-02','New Cashier','cashier','unused');
  insert into public.devices(id,stall_id,device_name,activation_code_hash,activation_expires_at)
  values (fresh_device,fresh_stall,'New POS',encode(digest('NEW-DEVICE-CODE','sha256'),'hex'),now()+interval '10 minutes');
  insert into public.app_sessions(user_id,token_hash,expires_at)
  values (fresh_cashier,encode(digest(fresh_token,'sha256'),'hex'),now()+interval '1 hour');
  perform set_config('role', 'service_role', true);
  permit := public.issue_pos_request_permit(fresh_hardware,gen_random_uuid(),key_a,encode(digest(fresh_token,'sha256'),'hex'),
    'POST','/rpc/activate_pos_device',null);
  perform set_config('role', 'anon', true);
  perform set_config('request.path','/rpc/activate_pos_device',true);
  perform set_config('request.headers',jsonb_build_object(
    'x-device-id',fresh_hardware,'x-session-token',fresh_token,'x-pos-permit',permit)::text,true);
  if public.current_app_user_id() is not null then raise exception 'An activation permit authenticated an unbound session'; end if;
  activated := public.activate_pos_device('NEW-DEVICE-CODE',fresh_hardware);
  if activated->>'device_id' is distinct from fresh_device::text then raise exception 'First activation failed under enforcement'; end if;
  perform set_config('role', 'service_role', true);
  permit := public.issue_pos_request_permit(fresh_hardware,gen_random_uuid(),key_a,encode(digest(fresh_token,'sha256'),'hex'),
    'POST','/rpc/activate_pos_device',null);
  perform set_config('role', 'anon', true);
  perform set_config('request.headers',jsonb_build_object(
    'x-device-id',fresh_hardware,'x-session-token',fresh_token,'x-pos-permit',permit)::text,true);
  activated := public.activate_pos_device('NEW-DEVICE-CODE',fresh_hardware);
  if activated->>'device_id' is distinct from fresh_device::text then raise exception 'Same-key activation retry failed'; end if;
  perform set_config('role', 'none', true);
  update private.pos_request_permits set expires_at=now()-interval '1 second' where permit_hash=encode(digest(permit,'sha256'),'hex');
  perform set_config('role', 'anon', true);
  if public.current_app_user_id() is not null then raise exception 'An expired permit authenticated a Cashier'; end if;
end;
$$;
rollback;
