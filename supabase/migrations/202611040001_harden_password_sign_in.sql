-- The custom password RPCs do not pass through Supabase Auth rate limits.
create table private.password_login_network_limits (
  source_network inet primary key,
  attempt_count integer not null,
  expires_at timestamptz not null
);
create index password_login_network_limits_expiry_idx
  on private.password_login_network_limits (expires_at);
alter table private.password_login_network_limits enable row level security;
revoke all on private.password_login_network_limits from public, anon, authenticated;

create function private.password_login_network_allowed()
returns boolean
language plpgsql set search_path = pg_catalog as $$
declare
  -- Supabase's hosted gateway sets this header. Never fall back to a
  -- caller-controlled forwarded address or device identifier.
  client_address text := nullif(current_setting('request.headers', true), '')::jsonb ->> 'cf-connecting-ip';
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
revoke all on function private.password_login_network_allowed() from public, anon, authenticated;

-- Prehash with a versioned domain and the per-password bcrypt salt so that
-- bcrypt sees 64 ASCII bytes and never truncates a Unicode passphrase.
create function private.password_bcrypt_input(p_password text, p_salt text)
returns text
language sql immutable strict set search_path = pg_catalog, extensions, public as $$
  select encode(digest('ice-cream-password-v1:' || left(p_salt, 29) || ':' || normalize(p_password), 'sha256'), 'hex');
$$;
revoke all on function private.password_bcrypt_input(text, text) from public, anon, authenticated;

create function private.hash_new_password(p_password text, p_account_words text[])
returns text
language plpgsql set search_path = pg_catalog, extensions, public as $$
declare
  normalized text := normalize(p_password);
  candidate text := lower(btrim(normalized));
  salt text;
begin
  if normalized is null or char_length(normalized) not between 15 and 128 then
    raise exception 'Use a password with 15 to 128 characters.' using errcode = '22023';
  end if;

  if candidate ~ '^[[:space:]]*$' or candidate ~ '^(.{1,4})\1+$'
    or candidate ~ '^(1234567890?|qwerty(uiop)?|p[a@]ssw[o0]rd|admin|letmein|iloveyou|welcome|coolerz|ice[ -]?cream)+[0-9!@#$%^&*._ -]*$'
    or candidate in ('correct horse battery staple', 'this is my password', 'please let me in',
      'qwertyuiopasdfghjkl', 'abcdefghijklmnopqrstuvwxyz', '123456789abcdef',
      '1q2w3e4r5t6y7u8i9o0p', 'ice cream system', 'coolerz ice cream', 'coolerz icecream')
    or exists (select 1 from unnest(p_account_words) as word(value)
      where candidate = lower(btrim(normalize(word.value)))) then
    raise exception 'Choose a less predictable password. Avoid common passwords and account details.' using errcode = '22023';
  end if;

  salt := gen_salt('bf', 10);
  return 'v1:' || crypt(private.password_bcrypt_input(normalized, salt), salt);
end;
$$;
revoke all on function private.hash_new_password(text, text[]) from public, anon, authenticated;

create or replace function private.password_login_allowed(p_user_id uuid, p_password text)
returns boolean
language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  target public.app_users%rowtype;
  failures private.password_login_failures%rowtype;
  checked_at timestamptz;
  stored_hash text;
  password_input text := p_password;
  next_count integer;
begin
  select * into target from public.app_users
  where id = p_user_id and is_active and deleted_at is null for update;
  if not found then return false; end if;

  checked_at := clock_timestamp();
  select * into failures from private.password_login_failures where user_id = p_user_id;
  if failures.blocked_until > checked_at then return false; end if;

  stored_hash := target.password_hash;
  if left(stored_hash, 3) = 'v1:' then
    stored_hash := substr(stored_hash, 4);
    password_input := private.password_bcrypt_input(p_password, stored_hash);
  end if;
  if stored_hash = crypt(password_input, stored_hash) then
    delete from private.password_login_failures where user_id = p_user_id;
    return true;
  end if;

  next_count := case
    when failures.user_id is null or failures.blocked_until is not null
      or failures.window_started_at <= checked_at - interval '15 minutes' then 1
    else failures.failed_count + 1
  end;
  insert into private.password_login_failures (user_id, failed_count, window_started_at, blocked_until)
  values (p_user_id, next_count, case when next_count = 1 then checked_at else failures.window_started_at end,
    case when next_count >= 5 then checked_at + interval '15 minutes' end)
  on conflict (user_id) do update set failed_count = excluded.failed_count,
    window_started_at = excluded.window_started_at, blocked_until = excluded.blocked_until;
  return false;
end;
$$;

create or replace function public.login_with_password(p_identifier text, p_password text)
returns table (session_token text, user_id uuid, stall_id uuid, display_name text,
  role public.app_role, expires_at timestamptz)
language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  matched_user public.app_users%rowtype;
  new_token text;
  session_expiry timestamptz;
begin
  if not private.password_login_network_allowed() then return; end if;

  select u.* into matched_user from public.app_users u
  where u.is_active and u.deleted_at is null and (
    (u.role = 'system_admin' and u.email = lower(trim(p_identifier)))
    or (u.role = 'owner' and u.username = lower(trim(p_identifier)))
  ) for update of u;
  if not found then return; end if;
  if not private.password_login_allowed(matched_user.id, p_password) then return; end if;

  new_token := encode(gen_random_bytes(32), 'hex');
  session_expiry := now() + interval '12 hours';
  insert into public.app_sessions(user_id, token_hash, expires_at)
  values (matched_user.id, encode(digest(new_token, 'sha256'), 'hex'), session_expiry);
  return query select new_token, matched_user.id, matched_user.stall_id,
    matched_user.display_name, matched_user.role, session_expiry;
end;
$$;

create or replace function public.login_pos_with_password_unchecked(
  p_stall_code text, p_employee_id text, p_password text)
returns table (session_token text, user_id uuid, stall_id uuid, display_name text,
  role public.app_role, expires_at timestamptz, device_id uuid, is_activated boolean)
language plpgsql security definer set search_path = pg_catalog, extensions, public as $$
declare
  matched_user public.app_users%rowtype;
  matched_device public.devices%rowtype;
  new_token text;
  session_expiry timestamptz;
  request_headers jsonb := coalesce(nullif(current_setting('request.headers', true), ''), '{}')::jsonb;
  hardware_id_value text;
begin
  hardware_id_value := nullif(trim(request_headers ->> 'x-device-id'), '');
  if hardware_id_value is null then raise exception 'DEVICE_ID_REQUIRED' using errcode = '22023'; end if;
  if not private.password_login_network_allowed() then return; end if;

  select u.* into matched_user from public.app_users u
  join public.stalls s on s.id = u.stall_id
  where s.code = upper(trim(p_stall_code)) and s.deleted_at is null
    and u.employee_id = upper(trim(p_employee_id)) and u.role = 'cashier'
    and u.is_active and u.deleted_at is null for update of u;
  if not found then return; end if;
  if not private.password_login_allowed(matched_user.id, p_password) then return; end if;

  select d.* into matched_device from public.devices d
  where d.stall_id = matched_user.stall_id and d.hardware_id = hardware_id_value
    and d.is_active and d.deleted_at is null
  order by d.updated_at desc limit 1;

  new_token := encode(gen_random_bytes(32), 'hex');
  session_expiry := now() + interval '12 hours';
  insert into public.app_sessions(user_id, token_hash, expires_at)
  values (matched_user.id, encode(digest(new_token, 'sha256'), 'hex'), session_expiry);
  return query select new_token, matched_user.id, matched_user.stall_id,
    matched_user.display_name, matched_user.role, session_expiry,
    matched_device.id, matched_device.id is not null;
end;
$$;

create or replace function public.save_managed_user(
  p_stall_id uuid, p_username text, p_employee_id text, p_display_name text,
  p_role public.app_role, p_password text default null, p_user_id uuid default null,
  p_is_active boolean default true
)
returns jsonb language plpgsql security definer set search_path = pg_catalog, public as $$
declare
  target public.app_users%rowtype;
  saved public.app_users%rowtype;
  normalized_username text := nullif(lower(trim(p_username)), '');
  normalized_employee_id text := nullif(upper(trim(p_employee_id)), '');
  new_password_hash text;
begin
  if not public.can_manage_stall(p_stall_id) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if p_role = 'system_admin' or (public.is_owner() and p_role <> 'cashier') then raise exception 'ROLE_NOT_ALLOWED' using errcode = '42501'; end if;
  if nullif(trim(p_display_name), '') is null then raise exception 'Display name is required' using errcode = '22023'; end if;
  if p_role = 'owner' and normalized_username is null then raise exception 'Username is required for an Owner' using errcode = '22023'; end if;
  if p_role = 'cashier' and normalized_employee_id is null then raise exception 'Employee ID is required for a Cashier' using errcode = '22023'; end if;

  if p_user_id is not null then
    select * into target from public.app_users where id = p_user_id and deleted_at is null for update;
    if not found or target.role = 'system_admin' or (public.is_owner() and (target.role <> 'cashier' or target.stall_id <> p_stall_id)) then
      raise exception 'USER_NOT_MANAGEABLE' using errcode = '42501';
    end if;
  end if;

  if p_user_id is null or p_password is not null then
    new_password_hash := private.hash_new_password(p_password, array[
      normalized_username, normalized_employee_id, p_display_name,
      (select code from public.stalls where id = p_stall_id)
    ]);
  end if;

  if p_user_id is null then
    insert into public.app_users (stall_id, username, employee_id, display_name, role, password_hash, is_active)
    values (p_stall_id, case when p_role = 'owner' then normalized_username end,
      case when p_role = 'cashier' then normalized_employee_id end,
      trim(p_display_name), p_role, new_password_hash, p_is_active) returning * into saved;
  else
    update public.app_users set stall_id = p_stall_id, email = null,
      username = case when p_role = 'owner' then normalized_username end,
      employee_id = case when p_role = 'cashier' then normalized_employee_id end,
      display_name = trim(p_display_name), role = p_role,
      password_hash = coalesce(new_password_hash, password_hash),
      is_active = p_is_active where id = p_user_id returning * into saved;
  end if;
  if saved.role = 'owner' then
    insert into public.owner_stall_access (user_id, stall_id) values (saved.id, p_stall_id) on conflict do nothing;
  else
    delete from public.owner_stall_access where user_id = saved.id;
  end if;
  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), p_stall_id, case when p_user_id is null then 'user.created' else 'user.updated' end,
    'app_user', saved.id::text, jsonb_build_object('role', saved.role, 'active', saved.is_active));
  return jsonb_build_object('id', saved.id, 'stall_id', saved.stall_id, 'email', saved.email, 'username', saved.username,
    'employee_id', saved.employee_id, 'display_name', saved.display_name, 'role', saved.role,
    'is_active', saved.is_active, 'updated_at', saved.updated_at);
end;
$$;

create or replace function public.create_initial_system_admin(
  p_stall_name text, p_stall_code text, p_email text, p_password text, p_display_name text
)
returns uuid language plpgsql security definer set search_path = pg_catalog, public as $$
declare
  new_stall_id uuid;
  new_password_hash text;
begin
  if exists (select 1 from public.app_users) then raise exception 'Initial account already created'; end if;
  new_password_hash := private.hash_new_password(p_password, array[
    p_stall_name, p_stall_code, p_email, split_part(p_email, '@', 1), p_display_name
  ]);
  insert into public.stalls (name, code) values (trim(p_stall_name), upper(trim(p_stall_code))) returning id into new_stall_id;
  insert into public.app_users (stall_id, email, display_name, role, password_hash)
  values (new_stall_id, lower(trim(p_email)), trim(p_display_name), 'system_admin', new_password_hash);
  return new_stall_id;
end;
$$;
