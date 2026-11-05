-- This endpoint accepts only the bearer being signed out. It also works
-- before administrator step-up and is safe to retry after a lost response.
create function public.revoke_app_session()
returns void language sql security definer set search_path = pg_catalog, extensions, public as $$
  update public.app_sessions
  set revoked_at = now()
  where token_hash = encode(digest(private.request_identity_header('x-session-token'), 'sha256'), 'hex')
    and revoked_at is null and expires_at > now();
$$;
revoke all on function public.revoke_app_session() from public, anon, authenticated;
grant execute on function public.revoke_app_session() to anon, authenticated;
