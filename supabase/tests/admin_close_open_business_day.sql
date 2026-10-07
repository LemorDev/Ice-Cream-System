begin;
set local search_path = public, extensions;

do $$
declare
  stall_id uuid := gen_random_uuid();
  admin_id uuid := gen_random_uuid();
  cashier_id uuid := gen_random_uuid();
  device_id uuid := gen_random_uuid();
  day_id uuid := gen_random_uuid();
  admin_token text := encode(gen_random_bytes(32), 'hex');
  cashier_token text := encode(gen_random_bytes(32), 'hex');
  result jsonb;
begin
  insert into public.stalls(id, name, code) values (stall_id, 'Admin close fixture', 'CLOSE-' || substr(stall_id::text, 1, 8));
  insert into public.app_users(id, stall_id, email, display_name, role, password_hash) values
    (admin_id, stall_id, admin_id::text || '@example.invalid', 'Admin', 'system_admin', 'unused'),
    (cashier_id, stall_id, cashier_id::text || '@example.invalid', 'Cashier', 'cashier', 'unused');
  insert into public.app_sessions(user_id, token_hash, expires_at) values
    (admin_id, encode(digest(admin_token, 'sha256'), 'hex'), now() + interval '1 hour'),
    (cashier_id, encode(digest(cashier_token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.devices(id, stall_id, device_name, activation_code_hash, hardware_id, is_active)
    values (device_id, stall_id, 'Uninstalled POS', 'unused', 'uninstalled-hardware', true);
  insert into public.business_days(id, stall_id, device_id, cashier_id, business_date, opened_at)
    values (day_id, stall_id, device_id, cashier_id, (now() at time zone 'Asia/Manila')::date, now() - interval '2 hours');

  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token)::text, true);
  begin
    perform public.admin_close_open_business_day(day_id, 0, 'Phone was uninstalled and local data is lost.');
    raise exception 'Cashier was allowed to remotely close a day';
  exception when insufficient_privilege then null;
  end;

  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  begin
    perform public.admin_close_open_business_day(day_id, 0, 'short');
    raise exception 'Uninformative close reason was accepted';
  exception when sqlstate '22023' then null;
  end;
  result := public.admin_close_open_business_day(day_id, 123.45, 'Development APK was uninstalled and its local queue is unavailable.');

  if result ->> 'status' <> 'closed'
    or not exists (select 1 from public.business_days where id = day_id and closed_at is not null and closing_cash_total = 123.45)
    or (select is_active from public.devices where id = device_id)
    or not exists (select 1 from public.daily_store_closings where business_day_id = day_id and collected_cash = 123.45)
    or not exists (select 1 from public.security_audit_log where target_id = day_id::text and action = 'business_day.admin_closed') then
    raise exception 'Admin close did not close, reconcile, deactivate, and audit the open day';
  end if;
end $$;

rollback;
