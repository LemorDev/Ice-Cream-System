-- Run on a disposable database after migrations; no fixture data is retained.
begin;
set local search_path = public, extensions;

do $$
declare
  stall_id uuid := gen_random_uuid();
  cashier_id uuid := gen_random_uuid();
  fixture_device_id uuid := gen_random_uuid();
  server_day_id uuid := gen_random_uuid();
  local_day_id uuid := gen_random_uuid();
  deduction_id uuid := gen_random_uuid();
  closing_id uuid := gen_random_uuid();
  token text := encode(gen_random_bytes(32), 'hex');
  close_date date := (now() at time zone 'Asia/Manila')::date;
  opened_at timestamptz := ((now() at time zone 'Asia/Manila')::date::timestamp at time zone 'Asia/Manila') + interval '1 hour';
  response jsonb;
begin
  insert into public.stalls (id, name, code) values
    (stall_id, 'Deduction fixture', 'DEDUCT-' || substr(stall_id::text, 1, 8));
  insert into public.app_users (id, stall_id, email, display_name, role, password_hash)
    values (cashier_id, stall_id, cashier_id::text || '@example.invalid', 'Cashier', 'cashier', 'unused');
  insert into public.app_sessions (user_id, token_hash, expires_at)
    values (cashier_id, encode(digest(token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.devices (id, stall_id, device_name, activation_code_hash, hardware_id)
    values (fixture_device_id, stall_id, 'Deduction POS', 'unused', 'deduction-test-device');
  insert into public.business_days (id, stall_id, device_id, cashier_id, business_date, opened_at, closed_at)
    values (server_day_id, stall_id, fixture_device_id, cashier_id, close_date, opened_at, opened_at + interval '1 hour');
  insert into public.transactions (stall_id, device_id, cashier_id, receipt_number, subtotal, total_amount, occurred_at)
    values (stall_id, fixture_device_id, cashier_id, gen_random_uuid()::text, 100, 100, opened_at + interval '10 minutes');
  perform set_config('request.headers', jsonb_build_object('x-session-token', token, 'x-device-id', 'deduction-test-device')::text, true);

  response := public.push_revenue_deduction(jsonb_build_object(
    'id', deduction_id, 'stall_id', stall_id, 'business_day_id', local_day_id,
    'business_date', close_date, 'amount', 10, 'reason', 'Customer refund',
    'cashier_id', cashier_id, 'occurred_at', opened_at + interval '20 minutes'));
  if response ->> 'status' <> 'accepted' then raise exception 'First deduction was not accepted'; end if;
  if (public.push_revenue_deduction(jsonb_build_object(
    'id', deduction_id, 'stall_id', stall_id, 'business_day_id', local_day_id,
    'business_date', close_date, 'amount', 10, 'reason', 'Customer refund',
    'cashier_id', cashier_id, 'occurred_at', opened_at + interval '20 minutes')) ->> 'status') <> 'duplicate' then
    raise exception 'Retry created a duplicate deduction';
  end if;
  perform public.push_revenue_deduction(jsonb_build_object(
    'id', gen_random_uuid(), 'stall_id', stall_id, 'business_day_id', local_day_id,
    'business_date', close_date, 'amount', 5, 'reason', 'Damaged item',
    'cashier_id', cashier_id, 'occurred_at', opened_at + interval '30 minutes'));

  response := public.push_daily_store_closing(jsonb_build_object(
    'id', closing_id, 'stall_id', stall_id, 'business_day_id', local_day_id,
    'device_id', fixture_device_id,
    'business_date', close_date, 'collected_cash', 85, 'closed_at', opened_at + interval '1 hour',
    'revenue_deduction', 15, 'deduction_reason', 'See recorded deductions'));
  if response ->> 'status' <> 'accepted' or not exists (
    select 1 from public.daily_store_closings where id = closing_id
      and business_day_id = server_day_id and device_id = fixture_device_id
      and gross_sales = 100 and revenue_deduction = 15 and expected_cash = 85
  ) then raise exception 'Closing did not reconcile server IDs and both deductions'; end if;

  perform public.push_revenue_deduction(jsonb_build_object(
    'id', gen_random_uuid(), 'stall_id', stall_id, 'business_day_id', local_day_id,
    'business_date', close_date, 'amount', 5, 'reason', 'Rent already in IMS overhead',
    'affects_profit', false, 'cashier_id', cashier_id,
    'occurred_at', opened_at + interval '35 minutes'));
  response := public.push_daily_store_closing(jsonb_build_object(
    'id', closing_id, 'stall_id', stall_id, 'business_day_id', local_day_id,
    'device_id', fixture_device_id,
    'business_date', close_date, 'collected_cash', 80, 'closed_at', opened_at + interval '1 hour',
    'revenue_deduction', 20, 'deduction_reason', 'See recorded deductions'));
  if response ->> 'status' <> 'accepted' or not exists (
    select 1 from public.daily_store_closings where id = closing_id
      and revenue_deduction = 20 and expected_cash = 80
      and net_profit = gross_sales - cogs - waste_cost - overhead_cost - 15
  ) then raise exception 'Cash-only deduction was counted twice in profit'; end if;
  perform public.push_daily_store_closing(jsonb_build_object(
    'id', closing_id, 'stall_id', stall_id, 'business_day_id', local_day_id,
    'device_id', fixture_device_id,
    'business_date', close_date, 'collected_cash', 80, 'closed_at', opened_at + interval '1 hour',
    'revenue_deduction', 20, 'deduction_reason', 'See recorded deductions'));
  if not exists (select 1 from public.daily_store_closings where id = closing_id
    and net_profit = gross_sales - cogs - waste_cost - overhead_cost - 15) then
    raise exception 'Closing retry counted cash-only deduction twice';
  end if;

  begin
    perform public.push_revenue_deduction(jsonb_build_object(
      'id', gen_random_uuid(), 'stall_id', stall_id, 'business_day_id', local_day_id,
      'business_date', close_date, 'amount', 1, 'reason', '  ',
      'cashier_id', cashier_id, 'occurred_at', opened_at + interval '40 minutes'));
    raise exception 'Reasonless deduction was accepted';
  exception when sqlstate '22023' then null;
  end;
end;
$$;

rollback;
