create table private.pos_device_proof_policy (
  singleton boolean primary key default true check (singleton),
  required boolean not null default false
);
insert into private.pos_device_proof_policy default values;

create table private.pos_device_proof_stalls (
  stall_id uuid primary key references public.stalls(id) on delete cascade
);

create table private.pos_device_keys (
  device_id uuid primary key references public.devices(id) on delete cascade,
  public_key text not null,
  activation_hash text not null,
  retry_until timestamptz not null
);
create table private.pos_device_key_enrollments (
  device_id uuid primary key references public.devices(id) on delete cascade,
  code_hash text not null,
  expires_at timestamptz not null
);
create table private.pos_request_permits (
  hardware_id text not null,
  nonce uuid not null,
  permit_hash text not null unique,
  public_key text not null,
  session_hash text,
  method text not null,
  path text not null,
  expires_at timestamptz not null default now() + interval '30 seconds',
  purge_at timestamptz not null default now() + interval '4 minutes',
  primary key (hardware_id, nonce)
);
create index pos_request_permits_purge_idx on private.pos_request_permits (purge_at);

alter table private.pos_device_proof_policy enable row level security;
alter table private.pos_device_proof_stalls enable row level security;
alter table private.pos_device_keys enable row level security;
alter table private.pos_device_key_enrollments enable row level security;
alter table private.pos_request_permits enable row level security;
revoke all on private.pos_device_proof_policy, private.pos_device_proof_stalls, private.pos_device_keys,
  private.pos_device_key_enrollments, private.pos_request_permits from public, anon, authenticated;

alter table public.app_sessions
  add column pos_device_id uuid references public.devices(id) on delete set null,
  add column pos_key_hash text;

-- The Edge verifier is the only caller. It verifies the Android signature over
-- the method, URL, body, session, timestamp and nonce before minting a permit.
-- Keep used nonces longer than the signature window, even after the permit expires.
create function public.issue_pos_request_permit(
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
  if p_path <> '/rpc/login_pos_with_password' and not exists (
    select 1 from public.app_sessions s join public.app_users u on u.id = s.user_id
    where s.token_hash = p_session_hash and s.revoked_at is null and s.expires_at > now()
      and u.role = 'cashier' and u.is_active and u.deleted_at is null
  ) then return null; end if;
  if p_path not in ('/rpc/login_pos_with_password', '/rpc/activate_pos_device')
    and not exists (
      select 1 from private.pos_device_keys k join public.devices d on d.id = k.device_id
      where d.hardware_id = p_hardware_id and d.is_active and d.deleted_at is null and k.public_key = p_public_key
    ) then return null; end if;
  if exists (select 1 from private.pos_request_permits p
    where p.hardware_id = p_hardware_id and p.nonce = p_nonce and p.purge_at > now()) then return null; end if;
  if p_path in ('/rpc/login_pos_with_password', '/rpc/activate_pos_device')
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
revoke all on function public.issue_pos_request_permit(text,uuid,text,text,text,text,text) from public, anon, authenticated;
grant execute on function public.issue_pos_request_permit(text,uuid,text,text,text,text,text) to service_role;

create function private.request_pos_proof()
returns table(hardware_id text, public_key text)
language sql stable set search_path = pg_catalog, extensions, public as $$
  select p.hardware_id, p.public_key
  from private.pos_request_permits p
  where p.permit_hash = encode(digest(private.request_identity_header('x-pos-permit'), 'sha256'), 'hex')
    and p.expires_at > now()
    and p.hardware_id = private.request_identity_header('x-device-id')
    and p.session_hash is not distinct from encode(digest(private.request_identity_header('x-session-token'), 'sha256'), 'hex')
    and p.method = current_setting('request.method', true)
    and p.path = current_setting('request.path', true);
$$;

-- Enrolling a stall closes its legacy access immediately. The global switch
-- also closes legacy access on stalls that have yet to enroll.
create function private.pos_proof_required(p_stall_id uuid)
returns boolean language sql stable set search_path = pg_catalog as $$
  select exists (select 1 from private.pos_device_proof_policy where required)
    or exists (select 1 from private.pos_device_proof_stalls where stall_id = p_stall_id);
$$;
revoke all on function private.request_pos_proof(), private.pos_proof_required(uuid) from public, anon, authenticated;

create or replace function public.current_app_user_id()
returns uuid language sql stable security definer set search_path = pg_catalog, extensions, public as $$
  select s.user_id
  from private.active_app_session(private.request_identity_header('x-session-token')) s
  join public.app_users u on u.id = s.user_id
  join public.app_sessions session_row on session_row.id = s.session_id
  cross join private.admin_passkey_policy policy
  where (s.role <> 'system_admin' or not policy.required or s.verified_at is not null)
    and (s.role <> 'cashier' or not private.pos_proof_required(u.stall_id)
      or exists (
        select 1 from private.request_pos_proof() proof
        where (
          -- The activation wrapper alone sets this transaction-local value.
          current_setting('request.path', true) = '/rpc/activate_pos_device'
          and current_setting('pos.activation_session', true) = s.session_id::text
        ) or exists (
          select 1 from public.devices d join private.pos_device_keys k on k.device_id = d.id
          where d.id = session_row.pos_device_id and d.stall_id = u.stall_id
            and d.hardware_id = proof.hardware_id and d.is_active and d.deleted_at is null
            and k.public_key = proof.public_key
            and session_row.pos_key_hash = encode(digest(k.public_key, 'sha256'), 'hex')
        )
      ));
$$;

create or replace function public.login_pos_with_password(
  p_stall_code text, p_employee_id text, p_password text)
returns table (session_token text, user_id uuid, stall_id uuid, display_name text,
  role public.app_role, expires_at timestamptz, device_id uuid, is_activated boolean)
language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare signed_in record; proof record; known_device uuid; key_matches boolean;
begin
  select * into signed_in from public.login_pos_with_password_unchecked(p_stall_code, p_employee_id, p_password);
  if not found then return; end if;
  select * into proof from private.request_pos_proof();
  select exists (
    select 1 from private.pos_device_keys k
    where k.device_id = signed_in.device_id and k.public_key = proof.public_key
  ) into key_matches;
  if key_matches then
    known_device := signed_in.device_id;
    update public.app_sessions s
    set pos_device_id = known_device, pos_key_hash = encode(digest(proof.public_key, 'sha256'), 'hex')
    where s.token_hash = encode(digest(signed_in.session_token, 'sha256'), 'hex');
  elsif proof.public_key is null and not private.pos_proof_required(signed_in.stall_id) then
    known_device := signed_in.device_id;
  end if;
  if known_device is not null then
    update public.devices d set transfer_ready_at = null where d.id = known_device;
  end if;
  return query select signed_in.session_token, signed_in.user_id, signed_in.stall_id,
    signed_in.display_name, signed_in.role, signed_in.expires_at, known_device, known_device is not null;
end;
$$;

-- Trusted operators enroll or recover an existing handset in place. An
-- enrollment code never changes the device ID or transfers its operating day.
create function public.issue_pos_device_key_enrollment(p_device_id uuid)
returns text language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare code text := upper(encode(gen_random_bytes(24), 'hex'));
begin
  perform 1 from public.devices where id = p_device_id and hardware_id is not null
    and is_active and deleted_at is null for update;
  if not found then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '22023'; end if;
  insert into private.pos_device_key_enrollments (device_id, code_hash, expires_at)
  values (p_device_id, encode(digest(code, 'sha256'), 'hex'), now() + interval '15 minutes')
  on conflict (device_id) do update set code_hash = excluded.code_hash, expires_at = excluded.expires_at;
  return code;
end;
$$;
revoke all on function public.issue_pos_device_key_enrollment(uuid) from public, anon, authenticated;
grant execute on function public.issue_pos_device_key_enrollment(uuid) to service_role;

alter function public.activate_pos_device(text,text) set schema private;
revoke all on function private.activate_pos_device(text,text) from public, anon, authenticated;

create function public.activate_pos_device(p_activation_code text, p_hardware_id text)
returns jsonb language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  raw_session record;
  proof record;
  stall uuid;
  registered_id uuid;
  submitted_hash text := encode(digest(upper(trim(p_activation_code)), 'sha256'), 'hex');
  response jsonb;
begin
  select * into raw_session from private.active_app_session(private.request_identity_header('x-session-token'));
  if raw_session.role is distinct from 'cashier'::public.app_role then
    raise exception 'CASHIER_REQUIRED' using errcode = '42501';
  end if;
  select u.stall_id into stall from public.app_users u where u.id = raw_session.user_id;
  select * into proof from private.request_pos_proof();
  if proof.public_key is null then
    if private.pos_proof_required(stall) then raise exception 'POS_DEVICE_PROOF_REQUIRED' using errcode = '42501'; end if;
    return private.activate_pos_device(p_activation_code, p_hardware_id);
  end if;
  if proof.hardware_id is distinct from trim(p_hardware_id) then
    raise exception 'DEVICE_ID_MISMATCH' using errcode = '42501';
  end if;

  -- Serialize enrollment and rotation for the same active registration.
  select d.id into registered_id from public.devices d
  where d.hardware_id = proof.hardware_id and d.stall_id = stall and d.is_active and d.deleted_at is null for update;
  if registered_id is not null and exists (
    select 1 from private.pos_device_keys k
    where k.device_id = registered_id and k.public_key = proof.public_key
      and k.activation_hash = submitted_hash and k.retry_until > now()
  ) then
    response := jsonb_build_object('device_id', registered_id, 'stall_id', stall);
  else
    delete from private.pos_device_key_enrollments e
    where e.device_id = registered_id and e.code_hash = submitted_hash and e.expires_at > now();
    if found then
      response := jsonb_build_object('device_id', registered_id, 'stall_id', stall);
      insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id)
      values (raw_session.user_id, stall, 'device.key_enrolled', 'device', registered_id::text);
    else
      perform set_config('pos.activation_session', raw_session.session_id::text, true);
      response := private.activate_pos_device(p_activation_code, p_hardware_id);
      perform set_config('pos.activation_session', '', true);
      registered_id := (response->>'device_id')::uuid;
    end if;
    insert into private.pos_device_keys (device_id, public_key, activation_hash, retry_until)
    values (registered_id, proof.public_key, submitted_hash, now() + interval '15 minutes')
    on conflict (device_id) do update set public_key = excluded.public_key,
      activation_hash = excluded.activation_hash, retry_until = excluded.retry_until;
  end if;
  update public.app_sessions set pos_device_id = registered_id,
    pos_key_hash = encode(digest(proof.public_key, 'sha256'), 'hex') where id = raw_session.session_id;
  insert into private.pos_device_proof_stalls (stall_id) values (stall) on conflict do nothing;
  return response;
end;
$$;
revoke all on function public.activate_pos_device(text,text) from public, anon, authenticated;
grant execute on function public.activate_pos_device(text,text) to anon, authenticated;

-- Apply the same source-network counter to relay bootstrap and direct login.
create function private.password_login_network_allowed_from(client_address text)
returns boolean
language plpgsql set search_path = pg_catalog as $$
declare
  source inet;
  checked_at timestamptz := clock_timestamp();
  attempts integer;
  retry_seconds integer;
begin
  if client_address is null or client_address !~ '^[0-9A-Fa-f:.]{2,45}$'
      or not pg_input_is_valid(client_address, 'inet') then
    return true; -- Local calls still use the account limit.
  end if;

  source := client_address::inet;
  if family(source) = 6 then source := network(set_masklen(source, 64)); end if;

  insert into private.password_login_network_limits as limits (source_network, attempt_count, expires_at)
  values (source, 1, checked_at + interval '1 minute')
  on conflict (source_network) do update
    set attempt_count = case when limits.expires_at <= checked_at then 1 else limits.attempt_count + 1 end,
        expires_at = case when limits.expires_at <= checked_at then excluded.expires_at else limits.expires_at end
    where limits.expires_at <= checked_at or limits.attempt_count < 30
  returning attempt_count into attempts;

  if not found then
    select greatest(1, ceil(extract(epoch from expires_at - clock_timestamp())))::integer
      into retry_seconds from private.password_login_network_limits where source_network = source;
    -- Set the HTTP response without raising an error or rolling back counters.
    perform set_config('response.status', '429', true);
    perform set_config('response.headers', jsonb_build_array(jsonb_build_object('Retry-After', retry_seconds::text))::text, true);
    return false;
  end if;

  if attempts = 1 then
    -- Bound cleanup per request and skip rows another login is using.
    delete from private.password_login_network_limits where source_network in (
      select source_network from private.password_login_network_limits
      where expires_at < checked_at - interval '5 minutes'
      order by expires_at limit 20 for update skip locked
    );
  end if;
  return true;
end;
$$;
revoke all on function private.password_login_network_allowed_from(text) from public, anon, authenticated;

create or replace function private.password_login_network_allowed()
returns boolean language plpgsql set search_path = pg_catalog as $$
begin
  if exists (select 1 from private.request_pos_proof()) then return true; end if;
  return private.password_login_network_allowed_from(private.request_identity_header('cf-connecting-ip'));
end;
$$;
revoke all on function private.password_login_network_allowed() from public, anon, authenticated;
