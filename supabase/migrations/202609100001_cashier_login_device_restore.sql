-- Cashier sign-in is scoped to a stall code and restores an existing active
-- device binding when the same Android device signs in again.

create or replace function public.login_pos_with_password(
  p_stall_code text,
  p_email text,
  p_password text
)
returns table (
  session_token text,
  user_id uuid,
  stall_id uuid,
  display_name text,
  role public.app_role,
  expires_at timestamptz,
  device_id uuid,
  is_activated boolean
)
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  matched_user public.app_users%rowtype;
  matched_device public.devices%rowtype;
  new_token text := encode(gen_random_bytes(32), 'hex');
  session_expiry timestamptz := now() + interval '12 hours';
  request_headers jsonb := coalesce(nullif(current_setting('request.headers', true), ''), '{}')::jsonb;
  hardware_id_value text;
begin
  hardware_id_value := nullif(trim(request_headers ->> 'x-device-id'), '');
  if hardware_id_value is null then
    raise exception 'DEVICE_ID_REQUIRED' using errcode = '22023';
  end if;

  select u.* into matched_user
  from public.app_users u
  join public.stalls s on s.id = u.stall_id
  where s.code = upper(trim(p_stall_code))
    and s.deleted_at is null
    and u.email = lower(trim(p_email))
    and u.role = 'cashier'
    and u.is_active
    and u.deleted_at is null
    and u.password_hash = crypt(p_password, u.password_hash);

  if not found then
    raise exception 'Invalid stall code, email, or password' using errcode = '28000';
  end if;

  select d.* into matched_device
  from public.devices d
  where d.stall_id = matched_user.stall_id
    and d.hardware_id = hardware_id_value
    and d.is_active
    and d.deleted_at is null
  order by d.updated_at desc
  limit 1;

  insert into public.app_sessions (user_id, token_hash, expires_at)
  values (matched_user.id, encode(digest(new_token, 'sha256'), 'hex'), session_expiry);

  return query select
    new_token,
    matched_user.id,
    matched_user.stall_id,
    matched_user.display_name,
    matched_user.role,
    session_expiry,
    matched_device.id,
    matched_device.id is not null;
end;
$$;

revoke all on function public.login_pos_with_password(text,text,text) from public;
grant execute on function public.login_pos_with_password(text,text,text) to anon, authenticated;
