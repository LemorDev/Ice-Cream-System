-- Keep platform administration separate from Owner financial configuration.
-- System administrators control stall identity; Owners may update overhead only
-- for stalls assigned to them.

drop policy if exists "administrators update accessible stalls" on public.stalls;
revoke update on public.stalls from anon, authenticated;

create or replace function public.update_managed_stall(
  p_stall_id uuid,
  p_name text default null,
  p_code text default null,
  p_overhead_config jsonb default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  target public.stalls%rowtype;
  saved public.stalls%rowtype;
begin
  if not public.can_manage_stall(p_stall_id) then
    raise exception 'FORBIDDEN' using errcode = '42501';
  end if;

  select * into target
  from public.stalls
  where id = p_stall_id and deleted_at is null
  for update;

  if not found then
    raise exception 'Stall not found' using errcode = 'P0002';
  end if;

  if public.is_owner() and (
    (p_name is not null and trim(p_name) <> target.name)
    or (p_code is not null and upper(trim(p_code)) <> target.code)
  ) then
    raise exception 'SYSTEM_ADMIN_REQUIRED_FOR_STALL_IDENTITY' using errcode = '42501';
  end if;

  if public.is_system_admin() and (
    (p_name is not null and length(trim(p_name)) < 2)
    or (p_code is not null and length(trim(p_code)) < 2)
  ) then
    raise exception 'Stall name and code must contain at least 2 characters' using errcode = '22023';
  end if;

  if p_overhead_config is not null and jsonb_typeof(p_overhead_config) <> 'array' then
    raise exception 'Invalid overhead configuration' using errcode = '22023';
  end if;

  if p_overhead_config is not null and (
    jsonb_array_length(p_overhead_config) = 0
    or exists (
      select 1
      from jsonb_array_elements(p_overhead_config) item
      where jsonb_typeof(item) <> 'object'
        or nullif(trim(item ->> 'key'), '') is null
        or nullif(trim(item ->> 'label'), '') is null
        or case
          when jsonb_typeof(item -> 'dailyRate') = 'number'
            then (item ->> 'dailyRate')::numeric < 0
          else true
        end
    )
  ) then
    raise exception 'Invalid overhead configuration' using errcode = '22023';
  end if;

  update public.stalls
  set
    name = case when public.is_system_admin() and p_name is not null then trim(p_name) else target.name end,
    code = case when public.is_system_admin() and p_code is not null then upper(trim(p_code)) else target.code end,
    overhead_config = coalesce(p_overhead_config, target.overhead_config)
  where id = p_stall_id
  returning * into saved;

  insert into public.security_audit_log (actor_user_id, stall_id, action, target_type, target_id, details)
  values (
    public.current_app_user_id(),
    p_stall_id,
    'stall.settings_updated',
    'stall',
    p_stall_id::text,
    jsonb_build_object(
      'identity_updated', public.is_system_admin() and (p_name is not null or p_code is not null),
      'overhead_updated', p_overhead_config is not null
    )
  );

  return to_jsonb(saved);
end;
$$;

revoke all on function public.update_managed_stall(uuid,text,text,jsonb) from public;
grant execute on function public.update_managed_stall(uuid,text,text,jsonb) to anon, authenticated;
