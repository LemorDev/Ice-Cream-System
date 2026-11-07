-- Managed password resets immediately invalidate all existing web/POS sessions.
-- A reset also clears the private login cooldown so the verified user can sign in.
create function private.revoke_sessions_on_user_change()
returns trigger language plpgsql security definer set search_path = public, private as $$
begin
  if new.password_hash is distinct from old.password_hash
    or new.is_active is distinct from old.is_active
    or new.role is distinct from old.role
    or new.stall_id is distinct from old.stall_id then
    update public.app_sessions set revoked_at = now()
    where user_id = new.id and revoked_at is null;
  end if;
  if new.password_hash is distinct from old.password_hash then
    delete from private.password_login_failures where user_id = new.id;
    insert into public.security_audit_log(actor_user_id, stall_id, action, target_type, target_id, details)
    values (public.current_app_user_id(), new.stall_id, 'user.credentials_reset', 'app_user', new.id::text,
      jsonb_build_object('sessions_revoked', true, 'cooldown_cleared', true));
  end if;
  return new;
end;
$$;
revoke all on function private.revoke_sessions_on_user_change() from public, anon, authenticated;

create trigger app_user_revoke_sessions_after_change
after update of password_hash, is_active, role, stall_id on public.app_users
for each row execute function private.revoke_sessions_on_user_change();
