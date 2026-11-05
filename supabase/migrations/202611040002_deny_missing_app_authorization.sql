-- PostgREST roles can invoke RPCs on behalf of custom app sessions. A gateway
-- role is not an app identity, and a missing identity must yield false in
-- authorization predicates used by PL/pgSQL guards and RLS.
create or replace function public.current_app_user_id()
returns uuid
language sql stable security definer set search_path = pg_catalog, extensions, public as $$
  select s.user_id
  from public.app_sessions s
  join public.app_users u on u.id = s.user_id
  where s.token_hash = encode(digest(nullif(
    nullif(current_setting('request.headers', true), '')::jsonb ->> 'x-session-token', ''
  ), 'sha256'), 'hex')
    and s.expires_at > now()
    and s.revoked_at is null
    and u.is_active
    and u.deleted_at is null
  limit 1;
$$;

create or replace function public.is_system_admin()
returns boolean
language sql stable security definer set search_path = pg_catalog as $$
  select public.current_app_role() is not distinct from 'system_admin'::public.app_role;
$$;

create or replace function public.is_owner()
returns boolean
language sql stable security definer set search_path = pg_catalog as $$
  select public.current_app_role() is not distinct from 'owner'::public.app_role;
$$;

create or replace function public.can_manage_stall(p_stall_id uuid)
returns boolean
language sql stable security definer set search_path = pg_catalog as $$
  select public.is_system_admin()
    and exists (select 1 from public.stalls s where s.id = p_stall_id and s.deleted_at is null);
$$;

create or replace function public.can_access_stall(p_stall_id uuid)
returns boolean
language sql stable security definer set search_path = pg_catalog as $$
  select case public.current_app_role()
    when 'system_admin' then true
    when 'owner' then exists (
      select 1 from public.owner_stall_access osa
      where osa.user_id = public.current_app_user_id() and osa.stall_id = p_stall_id
    )
    when 'cashier' then (p_stall_id = public.current_app_stall_id()) is true
    else false
  end;
$$;

create or replace function public.active_pos_device(p_device_id uuid)
returns boolean
language sql stable security definer set search_path = pg_catalog as $$
  select (public.current_app_role() is not distinct from 'cashier'::public.app_role)
    and exists (
      select 1 from public.devices d
      where d.id = p_device_id and d.stall_id = public.current_app_stall_id()
        and d.is_active and d.deleted_at is null
        and d.hardware_id = nullif(nullif(current_setting('request.headers', true), '')::jsonb ->> 'x-device-id', '')
    );
$$;
