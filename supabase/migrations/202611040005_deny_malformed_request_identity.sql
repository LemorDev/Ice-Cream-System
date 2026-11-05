-- An absent or unreadable request context provides no identity. Only string
-- header values are credentials. Keep the JSON cast behind a validity check.
create function private.request_identity_header(p_name text)
returns text
language sql stable set search_path = pg_catalog as $$
  select case when jsonb_typeof(value) = 'string' then nullif(value #>> '{}', '') end
  from (select case when pg_input_is_valid(headers, 'jsonb') then headers::jsonb -> p_name end as value
    from (select current_setting('request.headers', true) as headers) request) parsed;
$$;
revoke all on function private.request_identity_header(text) from public, anon, authenticated;

create or replace function public.current_app_user_id()
returns uuid language sql stable security definer set search_path = pg_catalog as $$
  select s.user_id
  from private.active_app_session(private.request_identity_header('x-session-token')) s
  cross join private.admin_passkey_policy policy
  where s.role <> 'system_admin' or not policy.required or s.verified_at is not null;
$$;

create or replace function public.is_system_admin()
returns boolean language sql stable security definer set search_path = pg_catalog as $$
  select exists (
    select 1
    from private.active_app_session(private.request_identity_header('x-session-token')) s
    cross join private.admin_passkey_policy policy
    where s.role = 'system_admin' and (not policy.required or s.verified_at > now() - interval '15 minutes')
  );
$$;

create or replace function public.active_pos_device(p_device_id uuid)
returns boolean
language sql stable security definer set search_path = pg_catalog as $$
  select (public.current_app_role() is not distinct from 'cashier'::public.app_role)
    and exists (
      select 1 from public.devices d
      where d.id = p_device_id and d.stall_id = public.current_app_stall_id()
        and d.is_active and d.deleted_at is null
        and d.hardware_id = private.request_identity_header('x-device-id')
    );
$$;
