-- System administrators sign in with email, owners with usernames, and
-- cashiers with an employee ID that is unique within their stall.

alter table public.app_users alter column email drop not null;
alter table public.app_users add column if not exists username text;
alter table public.app_users add column if not exists employee_id text;

-- Preserve sign-in access for existing users until an administrator updates
-- their account with the new identifier.
update public.app_users
set username = lower(trim(email))
where role = 'owner' and username is null and email is not null;

update public.app_users
set employee_id = upper(trim(email))
where role = 'cashier' and employee_id is null and email is not null;

update public.app_users
set email = null
where role in ('owner', 'cashier');

create unique index if not exists app_users_username_key
  on public.app_users (lower(username)) where username is not null;
create unique index if not exists app_users_stall_employee_id_key
  on public.app_users (stall_id, upper(employee_id)) where employee_id is not null;

alter table public.app_users drop constraint if exists app_users_role_sign_in_identifier;

drop function if exists public.login_with_password(text, text);
drop function if exists public.login_pos_with_password(text, text, text);
drop function if exists public.login_pos_with_password_unchecked(text, text, text);
drop function if exists public.list_managed_users(uuid);
drop function if exists public.save_managed_user(uuid, text, text, public.app_role, text, uuid, boolean);

create or replace function public.login_with_password(p_identifier text, p_password text)
returns table (session_token text, user_id uuid, stall_id uuid, display_name text,
  role public.app_role, expires_at timestamptz)
language plpgsql security definer set search_path = public, private, extensions as $$
declare
  matched_user public.app_users%rowtype;
  new_token text;
  session_expiry timestamptz;
begin
  select u.* into matched_user from public.app_users u
  where u.is_active and u.deleted_at is null and (
    (u.role = 'system_admin' and u.email = lower(trim(p_identifier)))
    or (u.role = 'owner' and u.username = lower(trim(p_identifier)))
  );
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
language plpgsql security definer set search_path = public, private, extensions as $$
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

  select u.* into matched_user from public.app_users u
  join public.stalls s on s.id = u.stall_id
  where s.code = upper(trim(p_stall_code)) and s.deleted_at is null
    and u.employee_id = upper(trim(p_employee_id)) and u.role = 'cashier'
    and u.is_active and u.deleted_at is null;
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

create or replace function public.login_pos_with_password(
  p_stall_code text, p_employee_id text, p_password text)
returns table (session_token text, user_id uuid, stall_id uuid, display_name text,
  role public.app_role, expires_at timestamptz, device_id uuid, is_activated boolean)
language plpgsql security definer set search_path = public as $$
declare signed_in record;
begin
  select * into signed_in from public.login_pos_with_password_unchecked(
    p_stall_code, p_employee_id, p_password);
  if not found then return; end if;
  if signed_in.device_id is not null then
    update public.devices set transfer_ready_at = null where id = signed_in.device_id;
  end if;
  return query select signed_in.session_token, signed_in.user_id, signed_in.stall_id,
    signed_in.display_name, signed_in.role, signed_in.expires_at,
    signed_in.device_id, signed_in.is_activated;
end;
$$;

create or replace function public.list_managed_users(p_stall_id uuid)
returns table (
  id uuid, stall_id uuid, email text, username text, employee_id text, display_name text, role public.app_role,
  is_active boolean, updated_at timestamptz, stall_ids uuid[]
)
language sql stable security definer set search_path = public as $$
  select u.id, u.stall_id, u.email, u.username, u.employee_id, u.display_name, u.role, u.is_active, u.updated_at,
    case when u.role = 'owner' then coalesce(array_agg(distinct osa.stall_id) filter (where osa.stall_id is not null), '{}'::uuid[])
      else array[u.stall_id] end
  from public.app_users u
  left join public.owner_stall_access osa on osa.user_id = u.id
  where u.deleted_at is null and public.can_manage_stall(p_stall_id) and (
      (public.is_system_admin() and ((u.stall_id = p_stall_id or exists (
        select 1 from public.owner_stall_access assigned where assigned.user_id = u.id and assigned.stall_id = p_stall_id
      )) and u.role <> 'system_admin'))
      or (public.is_owner() and u.stall_id = p_stall_id and u.role = 'cashier')
    )
  group by u.id order by u.display_name;
$$;

create or replace function public.save_managed_user(
  p_stall_id uuid, p_username text, p_employee_id text, p_display_name text,
  p_role public.app_role, p_password text default null, p_user_id uuid default null,
  p_is_active boolean default true
)
returns jsonb language plpgsql security definer set search_path = public, extensions as $$
declare target public.app_users%rowtype; saved public.app_users%rowtype; normalized_username text; normalized_employee_id text;
begin
  if not public.can_manage_stall(p_stall_id) then raise exception 'FORBIDDEN' using errcode = '42501'; end if;
  if p_role = 'system_admin' or (public.is_owner() and p_role <> 'cashier') then raise exception 'ROLE_NOT_ALLOWED' using errcode = '42501'; end if;
  normalized_username := nullif(lower(trim(p_username)), '');
  normalized_employee_id := nullif(upper(trim(p_employee_id)), '');
  if nullif(trim(p_display_name), '') is null then raise exception 'Display name is required' using errcode = '22023'; end if;
  if p_role = 'owner' and normalized_username is null then raise exception 'Username is required for an Owner' using errcode = '22023'; end if;
  if p_role = 'cashier' and normalized_employee_id is null then raise exception 'Employee ID is required for a Cashier' using errcode = '22023'; end if;
  if p_user_id is null and (p_password is null or length(p_password) < 8) then raise exception 'A password of at least 8 characters is required' using errcode = '22023'; end if;
  if p_password is not null and length(p_password) < 8 then raise exception 'Password must contain at least 8 characters' using errcode = '22023'; end if;

  if p_user_id is null then
    insert into public.app_users (stall_id, username, employee_id, display_name, role, password_hash, is_active)
    values (p_stall_id, case when p_role = 'owner' then normalized_username end, case when p_role = 'cashier' then normalized_employee_id end,
      trim(p_display_name), p_role, crypt(p_password, gen_salt('bf')), p_is_active) returning * into saved;
  else
    select * into target from public.app_users where id = p_user_id and deleted_at is null for update;
    if not found or target.role = 'system_admin' or (public.is_owner() and (target.role <> 'cashier' or target.stall_id <> p_stall_id)) then raise exception 'USER_NOT_MANAGEABLE' using errcode = '42501'; end if;
    update public.app_users set stall_id = p_stall_id, email = null,
      username = case when p_role = 'owner' then normalized_username end,
      employee_id = case when p_role = 'cashier' then normalized_employee_id end,
      display_name = trim(p_display_name), role = p_role,
      password_hash = case when p_password is null or p_password = '' then password_hash else crypt(p_password, gen_salt('bf')) end,
      is_active = p_is_active where id = p_user_id returning * into saved;
  end if;
  if saved.role = 'owner' then insert into public.owner_stall_access (user_id, stall_id) values (saved.id, p_stall_id) on conflict do nothing; else delete from public.owner_stall_access where user_id = saved.id; end if;
  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id, details)
  values (public.current_app_user_id(), p_stall_id, case when p_user_id is null then 'user.created' else 'user.updated' end,
    'app_user', saved.id::text, jsonb_build_object('role', saved.role, 'active', saved.is_active));
  return jsonb_build_object('id', saved.id, 'stall_id', saved.stall_id, 'email', saved.email, 'username', saved.username,
    'employee_id', saved.employee_id, 'display_name', saved.display_name, 'role', saved.role, 'is_active', saved.is_active, 'updated_at', saved.updated_at);
end;
$$;

revoke all on function public.list_managed_users(uuid) from public;
grant execute on function public.list_managed_users(uuid) to anon, authenticated;
revoke all on function public.save_managed_user(uuid, text, text, text, public.app_role, text, uuid, boolean) from public;
grant execute on function public.save_managed_user(uuid, text, text, text, public.app_role, text, uuid, boolean) to anon, authenticated;

revoke all on function public.login_with_password(text, text) from public;
grant execute on function public.login_with_password(text, text) to anon, authenticated;
revoke all on function public.login_pos_with_password_unchecked(text, text, text) from public, anon, authenticated;
revoke all on function public.login_pos_with_password(text, text, text) from public;
grant execute on function public.login_pos_with_password(text, text, text) to anon, authenticated;

-- Existing installations promote a selected Owner by their former email,
-- which was copied into username above; preserve that address as admin login.
create or replace function public.promote_user_to_system_admin(p_email text)
returns uuid language plpgsql security definer set search_path = public as $$
declare promoted_id uuid;
begin
  update public.app_users
  set role = 'system_admin', email = lower(trim(p_email)), username = null, employee_id = null
  where deleted_at is null and role = 'owner'
    and (username = lower(trim(p_email)) or email = lower(trim(p_email)))
  returning id into promoted_id;
  if promoted_id is null then raise exception 'Owner not found'; end if;
  delete from public.owner_stall_access where user_id = promoted_id;
  return promoted_id;
end;
$$;
revoke all on function public.promote_user_to_system_admin(text) from public, anon, authenticated;
