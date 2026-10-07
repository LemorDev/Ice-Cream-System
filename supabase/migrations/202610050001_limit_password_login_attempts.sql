-- Throttle both public password RPCs by account. A failed login must return
-- zero rows instead of raising: an exception would roll back its counter.
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

-- Development received this table during the earlier security repair before
-- CLI migration history was recorded; the verified shape is identical.
create table if not exists private.password_login_failures (
  user_id uuid primary key references public.app_users(id) on delete cascade,
  failed_count integer not null check (failed_count > 0),
  window_started_at timestamptz not null,
  blocked_until timestamptz
);
alter table private.password_login_failures enable row level security;
revoke all on private.password_login_failures from public, anon, authenticated;

create or replace function private.password_login_allowed(p_user_id uuid, p_password text)
returns boolean
language plpgsql security definer set search_path = public, private, extensions as $$
declare
  target public.app_users%rowtype;
  failures private.password_login_failures%rowtype;
  checked_at timestamptz := clock_timestamp();
  next_count integer;
begin
  -- Serialize concurrent guesses against this account before checking or
  -- updating the counter. The external RPCs look up only active users.
  select * into target from public.app_users
  where id = p_user_id and is_active and deleted_at is null
  for update;
  if not found then return false; end if;

  select * into failures from private.password_login_failures
  where user_id = p_user_id;
  if failures.blocked_until > checked_at then return false; end if;

  if target.password_hash = crypt(p_password, target.password_hash) then
    delete from private.password_login_failures where user_id = p_user_id;
    return true;
  end if;

  if failures.user_id is null then
    insert into private.password_login_failures(user_id, failed_count, window_started_at)
    values (p_user_id, 1, checked_at);
  else
    next_count := case
      when failures.window_started_at <= checked_at - interval '15 minutes' then 1
      else failures.failed_count + 1
    end;
    update private.password_login_failures
    set failed_count = next_count,
        window_started_at = case when next_count = 1 then checked_at else failures.window_started_at end,
        blocked_until = case when next_count >= 5 then checked_at + interval '15 minutes' else null end
    where user_id = p_user_id;
  end if;
  return false;
end;
$$;
revoke all on function private.password_login_allowed(uuid, text) from public, anon, authenticated;

create or replace function public.login_with_password(p_email text, p_password text)
returns table (session_token text, user_id uuid, stall_id uuid, display_name text,
  role public.app_role, expires_at timestamptz)
language plpgsql security definer set search_path = public, private, extensions as $$
declare
  matched_user public.app_users%rowtype;
  new_token text;
  session_expiry timestamptz;
begin
  select * into matched_user from public.app_users
  where email = lower(trim(p_email)) and is_active and deleted_at is null;
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
revoke all on function public.login_with_password(text, text) from public;
grant execute on function public.login_with_password(text, text) to anon, authenticated;

create or replace function public.login_pos_with_password_unchecked(
  p_stall_code text, p_email text, p_password text)
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
  if hardware_id_value is null then
    raise exception 'DEVICE_ID_REQUIRED' using errcode = '22023';
  end if;

  select u.* into matched_user from public.app_users u
  join public.stalls s on s.id = u.stall_id
  where s.code = upper(trim(p_stall_code)) and s.deleted_at is null
    and u.email = lower(trim(p_email)) and u.role = 'cashier'
    and u.is_active and u.deleted_at is null;
  if not found then return; end if;
  if not private.password_login_allowed(matched_user.id, p_password) then return; end if;

  select d.* into matched_device from public.devices d
  where d.stall_id = matched_user.stall_id
    and d.hardware_id = hardware_id_value and d.is_active and d.deleted_at is null
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
revoke all on function public.login_pos_with_password_unchecked(text, text, text)
  from public, anon, authenticated;

create or replace function public.login_pos_with_password(
  p_stall_code text, p_email text, p_password text)
returns table (session_token text, user_id uuid, stall_id uuid, display_name text,
  role public.app_role, expires_at timestamptz, device_id uuid, is_activated boolean)
language plpgsql security definer set search_path = public as $$
declare signed_in record;
begin
  select * into signed_in from public.login_pos_with_password_unchecked(
    p_stall_code, p_email, p_password);
  if not found then return; end if;
  if signed_in.device_id is not null then
    update public.devices set transfer_ready_at = null where id = signed_in.device_id;
  end if;
  return query select signed_in.session_token, signed_in.user_id, signed_in.stall_id,
    signed_in.display_name, signed_in.role, signed_in.expires_at,
    signed_in.device_id, signed_in.is_activated;
end;
$$;
revoke all on function public.login_pos_with_password(text, text, text) from public;
grant execute on function public.login_pos_with_password(text, text, text) to anon, authenticated;
