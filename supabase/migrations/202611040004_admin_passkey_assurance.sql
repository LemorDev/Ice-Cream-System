alter table public.app_sessions add column admin_verified_at timestamptz;

create table private.admin_passkey_policy (
  singleton boolean primary key default true check (singleton),
  required boolean not null default false
);
insert into private.admin_passkey_policy default values;

create table private.admin_passkeys (
  credential_id text primary key,
  user_id uuid not null references public.app_users(id) on delete cascade,
  public_key text not null,
  counter bigint not null check (counter >= 0)
);

create table private.admin_passkey_enrollments (
  user_id uuid primary key references public.app_users(id) on delete cascade,
  code_hash text not null,
  expires_at timestamptz not null
);

create table private.admin_passkey_challenges (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null unique references public.app_sessions(id) on delete cascade,
  challenge text not null,
  enrollment_hash text,
  expires_at timestamptz not null default (now() + interval '2 minutes')
);

alter table private.admin_passkey_policy enable row level security;
alter table private.admin_passkeys enable row level security;
alter table private.admin_passkey_enrollments enable row level security;
alter table private.admin_passkey_challenges enable row level security;
revoke all on private.admin_passkey_policy, private.admin_passkeys, private.admin_passkey_enrollments,
  private.admin_passkey_challenges from public, anon, authenticated;

-- The verifier needs to resolve a password-authenticated session before that
-- session has access to any Administrator data.
create function private.active_app_session(p_token text)
returns table (session_id uuid, user_id uuid, role public.app_role, verified_at timestamptz)
language sql stable set search_path = pg_catalog, extensions, public as $$
  select s.id, s.user_id, u.role, s.admin_verified_at
  from public.app_sessions s join public.app_users u on u.id = s.user_id
  where s.token_hash = encode(digest(nullif(p_token, ''), 'sha256'), 'hex')
    and s.expires_at > now() and s.revoked_at is null and u.is_active and u.deleted_at is null;
$$;
revoke all on function private.active_app_session(text) from public, anon, authenticated;

create or replace function public.current_app_user_id()
returns uuid language sql stable security definer set search_path = pg_catalog as $$
  select s.user_id
  from private.active_app_session(nullif(current_setting('request.headers', true), '')::jsonb ->> 'x-session-token') s
  cross join private.admin_passkey_policy policy
  where s.role <> 'system_admin' or not policy.required or s.verified_at is not null;
$$;

create or replace function public.is_system_admin()
returns boolean language sql stable security definer set search_path = pg_catalog as $$
  select exists (
    select 1
    from private.active_app_session(nullif(current_setting('request.headers', true), '')::jsonb ->> 'x-session-token') s
    cross join private.admin_passkey_policy policy
    where s.role = 'system_admin' and (not policy.required or s.verified_at > now() - interval '15 minutes')
  );
$$;

-- Trusted database operators issue a short-lived, one-use code out of band.
create function public.issue_admin_passkey_enrollment(p_user_id uuid)
returns text language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  code text := encode(gen_random_bytes(24), 'hex');
  admin_stall uuid;
begin
  select stall_id into admin_stall from public.app_users where id = p_user_id and role = 'system_admin'
    and is_active and deleted_at is null for update;
  if not found then
    raise exception 'ACTIVE_ADMIN_REQUIRED' using errcode = '42501';
  end if;
  insert into private.admin_passkey_enrollments(user_id, code_hash, expires_at)
  values (p_user_id, encode(digest(code, 'sha256'), 'hex'), now() + interval '15 minutes')
  on conflict (user_id) do update set code_hash = excluded.code_hash, expires_at = excluded.expires_at;
  insert into public.security_audit_log(stall_id, action, target_type, target_id)
  values (admin_stall, 'admin.passkey_enrollment_issued', 'app_user', p_user_id::text);
  return code;
end;
$$;
revoke all on function public.issue_admin_passkey_enrollment(uuid) from public, anon, authenticated, service_role;

create function public.revoke_admin_passkeys(p_user_id uuid)
returns void language plpgsql security definer set search_path = pg_catalog as $$
declare admin_stall uuid;
begin
  -- Take the same user lock as credential verification and enrollment.
  select stall_id into admin_stall from public.app_users where id = p_user_id for update;
  if not found then return; end if;
  delete from private.admin_passkeys where user_id = p_user_id;
  delete from private.admin_passkey_enrollments where user_id = p_user_id;
  update public.app_sessions set revoked_at = now() where user_id = p_user_id and revoked_at is null;
  insert into public.security_audit_log(stall_id, action, target_type, target_id)
  values (admin_stall, 'admin.passkeys_revoked', 'app_user', p_user_id::text);
end;
$$;
revoke all on function public.revoke_admin_passkeys(uuid) from public, anon, authenticated, service_role;

create function public.enable_admin_passkey_policy()
returns void language plpgsql security definer set search_path = pg_catalog as $$
begin
  if not exists (select 1 from public.app_users where role = 'system_admin' and is_active and deleted_at is null)
    or exists (select 1 from public.app_users u where u.role = 'system_admin' and u.is_active and u.deleted_at is null
      and not exists (select 1 from private.admin_passkeys k where k.user_id = u.id)) then
    raise exception 'ENROLL_ALL_ACTIVE_ADMINS_FIRST' using errcode = '22023';
  end if;
  update private.admin_passkey_policy set required = true;
end;
$$;
revoke all on function public.enable_admin_passkey_policy() from public, anon, authenticated, service_role;

-- Only the trusted WebAuthn Edge Function can call this verifier interface.
create function public.admin_passkey_context(p_token text)
returns jsonb language sql stable security definer set search_path = pg_catalog as $$
  select jsonb_build_object('user_id', s.user_id, 'keys', coalesce((
    select jsonb_agg(jsonb_build_object('id', k.credential_id, 'public_key', k.public_key, 'counter', k.counter))
    from private.admin_passkeys k where k.user_id = s.user_id), '[]'::jsonb))
  from private.active_app_session(p_token) s where s.role = 'system_admin';
$$;

create function public.begin_admin_passkey(p_token text, p_challenge text, p_enrollment_code text)
returns uuid language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  session record;
  enrollment_hash text;
  challenge_id uuid;
begin
  select * into session from private.active_app_session(p_token) where role = 'system_admin';
  if not found then raise exception 'ADMIN_SESSION_REQUIRED' using errcode = '42501'; end if;
  if p_enrollment_code is not null then
    select e.code_hash into enrollment_hash from private.admin_passkey_enrollments e
    where e.user_id = session.user_id and e.expires_at > now()
      and e.code_hash = encode(digest(p_enrollment_code, 'sha256'), 'hex');
    if not found then raise exception 'ENROLLMENT_CODE_INVALID' using errcode = '42501'; end if;
  end if;
  insert into private.admin_passkey_challenges as c (session_id, challenge, enrollment_hash)
  values (session.session_id, p_challenge, enrollment_hash)
  on conflict (session_id) do update set id = gen_random_uuid(), challenge = excluded.challenge,
    enrollment_hash = excluded.enrollment_hash, expires_at = excluded.expires_at
  returning id into challenge_id;
  return challenge_id;
end;
$$;

create function public.consume_admin_passkey_challenge(p_token text, p_challenge_id uuid, p_enrolling boolean)
returns jsonb language sql security definer set search_path = pg_catalog as $$
  delete from private.admin_passkey_challenges c using private.active_app_session(p_token) s
  where c.id = p_challenge_id and c.session_id = s.session_id and s.role = 'system_admin'
    and c.expires_at > now() and (c.enrollment_hash is not null) = p_enrolling
  returning jsonb_build_object('challenge', c.challenge, 'enrollment_hash', c.enrollment_hash);
$$;

create function public.finish_admin_passkey(
  p_token text, p_credential_id text, p_public_key text, p_old_counter bigint, p_new_counter bigint, p_enrollment_hash text
)
returns timestamptz language plpgsql security definer set search_path = pg_catalog as $$
declare
  session record;
  verified_at timestamptz;
  admin_stall uuid;
begin
  select * into session from private.active_app_session(p_token) where role = 'system_admin';
  if not found then raise exception 'ADMIN_SESSION_REQUIRED' using errcode = '42501'; end if;
  select stall_id into admin_stall from public.app_users where id = session.user_id for update;
  if not exists (select 1 from private.active_app_session(p_token) where role = 'system_admin') then
    raise exception 'ADMIN_SESSION_REQUIRED' using errcode = '42501';
  end if;
  if p_enrollment_hash is not null then
    delete from private.admin_passkey_enrollments where user_id = session.user_id
      and code_hash = p_enrollment_hash and expires_at > now();
    if not found then raise exception 'ENROLLMENT_CODE_INVALID' using errcode = '42501'; end if;
    insert into private.admin_passkeys(credential_id, user_id, public_key, counter)
    values (p_credential_id, session.user_id, p_public_key, p_new_counter);
  else
    update private.admin_passkeys set counter = p_new_counter where credential_id = p_credential_id
      and user_id = session.user_id and counter = p_old_counter;
    if not found then raise exception 'PASSKEY_CHANGED' using errcode = '42501'; end if;
  end if;
  update public.app_sessions set admin_verified_at = clock_timestamp() where id = session.session_id
  returning admin_verified_at into verified_at;
  insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id)
  values (session.user_id, admin_stall,
    case when p_enrollment_hash is null then 'admin.passkey_verified' else 'admin.passkey_enrolled' end,
    'app_user', session.user_id::text);
  return verified_at;
end;
$$;

revoke all on function public.admin_passkey_context(text), public.begin_admin_passkey(text,text,text),
  public.consume_admin_passkey_challenge(text,uuid,boolean), public.finish_admin_passkey(text,text,text,bigint,bigint,text)
  from public, anon, authenticated;
grant execute on function public.admin_passkey_context(text), public.begin_admin_passkey(text,text,text),
  public.consume_admin_passkey_challenge(text,uuid,boolean), public.finish_admin_passkey(text,text,text,bigint,bigint,text)
  to service_role;
