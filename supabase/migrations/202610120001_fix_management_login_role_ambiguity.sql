-- Repair databases that already installed the role-based sign-in migration.
-- The returned "role" field in this PL/pgSQL function conflicts with the
-- app_users.role column unless the table columns are qualified.
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

revoke all on function public.login_with_password(text, text) from public;
grant execute on function public.login_with_password(text, text) to anon, authenticated;
