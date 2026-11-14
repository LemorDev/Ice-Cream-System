-- A fingerprint credential is separate from the POS session revoked at sign-out.
-- It is redeemable only with proof from the same registered device key.
create table private.pos_fingerprint_credentials (
  device_id uuid primary key references public.devices(id) on delete cascade,
  user_id uuid not null references public.app_users(id) on delete cascade,
  device_key_hash text not null,
  secret_hash text not null unique,
  expires_at timestamptz not null
);
create index pos_fingerprint_credentials_user_idx on private.pos_fingerprint_credentials(user_id);
alter table private.pos_fingerprint_credentials enable row level security;
revoke all on private.pos_fingerprint_credentials from public, anon, authenticated;

create function private.revoke_pos_fingerprint_credential()
returns trigger language plpgsql security definer set search_path = pg_catalog as $$
begin
  delete from private.pos_fingerprint_credentials where user_id = new.id;
  return new;
end;
$$;
revoke all on function private.revoke_pos_fingerprint_credential() from public, anon, authenticated;

create trigger app_user_revoke_pos_fingerprint_after_change
after update of password_hash, is_active, role, stall_id, deleted_at on public.app_users
for each row when (old.password_hash is distinct from new.password_hash
  or old.is_active is distinct from new.is_active or old.role is distinct from new.role
  or old.stall_id is distinct from new.stall_id or old.deleted_at is distinct from new.deleted_at)
execute function private.revoke_pos_fingerprint_credential();

create function public.set_pos_fingerprint_credential(p_secret text default null)
returns boolean language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  proof record;
  cashier uuid;
  device uuid;
  key_hash text;
begin
  if p_secret is not null and p_secret !~ '^[0-9a-f]{64}$' then
    raise exception 'INVALID_FINGERPRINT_CREDENTIAL' using errcode = '22023';
  end if;
  select * into proof from private.request_pos_proof();
  select u.id into cashier from public.app_users u
  where u.id = public.current_app_user_id() and u.role = 'cashier'
    and u.is_active and u.deleted_at is null for update;
  key_hash := encode(digest(proof.public_key, 'sha256'), 'hex');
  select s.pos_device_id into device
  from private.active_app_session(private.request_identity_header('x-session-token')) active
  join public.app_sessions s on s.id = active.session_id
  where active.user_id = cashier and s.pos_key_hash = key_hash
    and public.active_pos_device(s.pos_device_id);
  if device is null then
    raise exception 'ACTIVE_CASHIER_DEVICE_REQUIRED' using errcode = '42501';
  end if;

  if p_secret is null then
    delete from private.pos_fingerprint_credentials c where c.device_id = device and c.user_id = cashier;
  else
    insert into private.pos_fingerprint_credentials (device_id, user_id, device_key_hash, secret_hash, expires_at)
    values (device, cashier, key_hash, encode(digest(p_secret, 'sha256'), 'hex'), now() + interval '90 days')
    on conflict (device_id) do update set user_id = excluded.user_id, device_key_hash = excluded.device_key_hash,
      secret_hash = excluded.secret_hash, expires_at = excluded.expires_at;
  end if;
  return true;
end;
$$;

create function public.login_pos_with_fingerprint(p_secret text)
returns table (session_token text, user_id uuid, stall_id uuid, display_name text,
  role public.app_role, expires_at timestamptz, device_id uuid, is_activated boolean)
language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  proof record;
  cashier public.app_users%rowtype;
  secret_digest text;
  key_hash text;
  device uuid;
  new_token text;
  session_expiry timestamptz;
begin
  if p_secret is null or p_secret !~ '^[0-9a-f]{64}$' then return; end if;
  select * into proof from private.request_pos_proof();
  if not found then return; end if;
  secret_digest := encode(digest(p_secret, 'sha256'), 'hex');
  key_hash := encode(digest(proof.public_key, 'sha256'), 'hex');

  -- Serialize with password resets and account changes before issuing a session.
  select u.* into cashier from public.app_users u
  join private.pos_fingerprint_credentials c on c.user_id = u.id
  join public.stalls s on s.id = u.stall_id
  where c.secret_hash = secret_digest and c.device_key_hash = key_hash
    and u.role = 'cashier' and u.is_active and u.deleted_at is null and s.deleted_at is null
  for update of u;
  if not found then return; end if;

  select d.id into device from private.pos_fingerprint_credentials c
  join public.devices d on d.id = c.device_id
  join private.pos_device_keys k on k.device_id = d.id
  where c.user_id = cashier.id and c.secret_hash = secret_digest and c.device_key_hash = key_hash
    and c.expires_at > now() and d.stall_id = cashier.stall_id
    and d.hardware_id = proof.hardware_id and d.is_active and d.deleted_at is null
    and k.public_key = proof.public_key
  for update of d;
  if not found then return; end if;

  new_token := encode(gen_random_bytes(32), 'hex');
  session_expiry := now() + interval '12 hours';
  insert into public.app_sessions(user_id, token_hash, expires_at, pos_device_id, pos_key_hash)
  values (cashier.id, encode(digest(new_token, 'sha256'), 'hex'), session_expiry, device, key_hash);
  update public.devices set transfer_ready_at = null where id = device;
  return query select new_token, cashier.id, cashier.stall_id, cashier.display_name,
    cashier.role, session_expiry, device, true;
end;
$$;

revoke all on function public.set_pos_fingerprint_credential(text), public.login_pos_with_fingerprint(text)
from public, anon, authenticated;
grant execute on function public.set_pos_fingerprint_credential(text), public.login_pos_with_fingerprint(text)
to anon, authenticated;

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
  elsif not exists (
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
