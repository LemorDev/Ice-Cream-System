-- The phone checks the cashier locally. Cloud sync still needs a short-lived,
-- device-bound session. A signed request may renew it during a 30-day grace
-- period, provided neither the session nor its device or cashier was revoked.
create function private.renewable_pos_session(p_session_hash text, p_hardware_id text, p_public_key text)
returns uuid language sql stable set search_path = pg_catalog, extensions, public as $$
  select s.id
  from public.app_sessions s
  join public.app_users u on u.id = s.user_id
  join public.stalls stall on stall.id = u.stall_id
  join public.devices d on d.id = s.pos_device_id and d.stall_id = stall.id
  join private.pos_device_keys k on k.device_id = d.id
  where s.token_hash = p_session_hash and s.revoked_at is null
    and s.expires_at > now() - interval '30 days'
    and u.role = 'cashier' and u.is_active and u.deleted_at is null and stall.deleted_at is null
    and d.is_active and d.deleted_at is null and d.transfer_ready_at is null
    and d.hardware_id = p_hardware_id and k.public_key = p_public_key
    and s.pos_key_hash = encode(digest(k.public_key, 'sha256'), 'hex');
$$;
revoke all on function private.renewable_pos_session(text,text,text) from public, anon, authenticated;

create function public.renew_pos_session()
returns timestamptz language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  proof record;
  session_id_value uuid;
  renewed_until timestamptz;
begin
  select * into proof from private.request_pos_proof();
  if not found then raise exception 'POS_DEVICE_PROOF_REQUIRED' using errcode = '42501'; end if;
  session_id_value := private.renewable_pos_session(
    encode(digest(private.request_identity_header('x-session-token'), 'sha256'), 'hex'),
    proof.hardware_id, proof.public_key);
  update public.app_sessions s set expires_at = greatest(s.expires_at, now() + interval '12 hours')
  where s.id = session_id_value and s.revoked_at is null
  returning s.expires_at into renewed_until;
  if not found then raise exception 'POS_SESSION_RENEWAL_DENIED' using errcode = '42501'; end if;
  return renewed_until;
end;
$$;
revoke all on function public.renew_pos_session() from public, anon, authenticated;
grant execute on function public.renew_pos_session() to anon, authenticated;

create or replace function public.revoke_app_session()
returns void language sql security definer set search_path = pg_catalog, extensions, public as $$
  update public.app_sessions
  set revoked_at = now()
  where token_hash = encode(digest(private.request_identity_header('x-session-token'), 'sha256'), 'hex')
    and revoked_at is null
    and (expires_at > now() or (pos_device_id is not null and expires_at > now() - interval '30 days'));
$$;

create or replace function public.issue_pos_request_permit(
  p_hardware_id text, p_nonce uuid, p_public_key text, p_session_hash text,
  p_method text, p_path text, p_client_address text)
returns text language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare permit text := encode(gen_random_bytes(32), 'hex');
begin
  if p_hardware_id is null or p_hardware_id !~ '^[0-9a-f]{1,16}$'
    or p_public_key is null or length(p_public_key) not between 100 and 150
    or p_nonce is null or p_method is null or p_method not in ('GET', 'POST') or p_path is null
    or (p_session_hash is not null and p_session_hash !~ '^[0-9a-f]{64}$') then
    return null;
  end if;
  if p_path in ('/rpc/login_pos_with_password', '/rpc/login_pos_with_fingerprint') then
    if p_session_hash is not null then return null; end if;
  elsif p_path = '/rpc/renew_pos_session' then
    if p_method <> 'POST' or private.renewable_pos_session(p_session_hash, p_hardware_id, p_public_key) is null then
      return null;
    end if;
  elsif not exists (
    select 1 from public.app_sessions s join public.app_users u on u.id = s.user_id
    where s.token_hash = p_session_hash and s.revoked_at is null and s.expires_at > now()
      and u.role = 'cashier' and u.is_active and u.deleted_at is null
  ) then return null; end if;
  if p_path not in ('/rpc/login_pos_with_password', '/rpc/activate_pos_device', '/rpc/renew_pos_session')
    and not exists (
      select 1 from private.pos_device_keys k join public.devices d on d.id = k.device_id
      where d.hardware_id = p_hardware_id and d.is_active and d.deleted_at is null and k.public_key = p_public_key
    ) then return null; end if;
  if exists (select 1 from private.pos_request_permits p
    where p.hardware_id = p_hardware_id and p.nonce = p_nonce and p.purge_at > now()) then return null; end if;
  if p_path in ('/rpc/login_pos_with_password', '/rpc/login_pos_with_fingerprint', '/rpc/activate_pos_device')
    and not private.password_login_network_allowed_from(p_client_address) then return null; end if;

  delete from private.pos_request_permits where (hardware_id, nonce) in (
    select p.hardware_id, p.nonce from private.pos_request_permits p
    where p.purge_at <= now() order by p.purge_at limit 50 for update skip locked
  );
  insert into private.pos_request_permits (hardware_id, nonce, permit_hash, public_key, session_hash, method, path)
  values (p_hardware_id, p_nonce, encode(digest(permit, 'sha256'), 'hex'), p_public_key,
    p_session_hash, p_method, p_path)
  on conflict (hardware_id, nonce) do nothing;
  return case when found then permit end;
end;
$$;
