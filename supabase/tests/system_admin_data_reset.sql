-- Run against a disposable database after every migration. All fixtures roll back.
begin;
set local search_path = public, extensions;

do $$
declare
  v_stall_id uuid := gen_random_uuid();
  admin_id uuid := gen_random_uuid();
  owner_id uuid := gen_random_uuid();
  product_id uuid := gen_random_uuid();
  v_transaction_id uuid := gen_random_uuid();
  device_id uuid := gen_random_uuid();
  v_business_day_id uuid := gen_random_uuid();
  admin_token text := encode(gen_random_bytes(32), 'hex');
  owner_token text := encode(gen_random_bytes(32), 'hex');
  result jsonb;
begin
  insert into public.stalls (id, name, code)
  values (v_stall_id, 'Reset fixture', 'RESET-' || substr(v_stall_id::text, 1, 8));
  insert into public.app_users (id, stall_id, email, display_name, role, password_hash) values
    (admin_id, v_stall_id, admin_id::text || '@example.invalid', 'Reset admin', 'system_admin', 'unused'),
    (owner_id, v_stall_id, owner_id::text || '@example.invalid', 'Reset owner', 'owner', 'unused');
  insert into public.app_sessions (user_id, token_hash, expires_at) values
    (admin_id, encode(digest(admin_token, 'sha256'), 'hex'), now() + interval '1 hour'),
    (owner_id, encode(digest(owner_token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.products (id, stall_id, sku, name, unit, product_type, base_unit)
  values (product_id, v_stall_id, 'RESET-PRODUCT', 'Reset product', 'piece', 'raw', 'piece');
  insert into public.devices (id, stall_id, device_name, activation_code_hash)
  values (device_id, v_stall_id, 'Reset device', encode(digest(device_id::text, 'sha256'), 'hex'));
  insert into public.business_days (id, stall_id, device_id, cashier_id, business_date, opened_at)
  values (v_business_day_id, v_stall_id, device_id, owner_id, current_date, now() - interval '8 hours');
  insert into public.inventory_ledger (stall_id, product_id, quantity_delta, movement_type)
  values (v_stall_id, product_id, 12, 'opening_balance');
  insert into public.transactions (id, stall_id, receipt_number, subtotal, total_amount)
  values (v_transaction_id, v_stall_id, 'RESET-RECEIPT', 250, 250);
  insert into public.transaction_items (transaction_id, product_id, product_name, quantity, unit_price, line_total)
  values (v_transaction_id, product_id, 'Reset product', 1, 250, 250);
  insert into public.daily_store_closings (stall_id, business_day_id, business_date, gross_sales, expected_cash, collected_cash)
  values (v_stall_id, v_business_day_id, current_date, 250, 250, 250);
  insert into public.revenue_deductions (id, stall_id, business_day_id, amount, reason, cashier_id, occurred_at)
  values (gen_random_uuid(), v_stall_id, v_business_day_id, 10, 'Reset fixture deduction', owner_id, now());

  perform set_config('request.headers', jsonb_build_object('x-session-token', owner_token)::text, true);
  begin
    perform public.reset_stall_transactions(v_stall_id);
    raise exception 'Owner was allowed to reset transactions';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.reset_stall_stock(v_stall_id);
    raise exception 'Owner was allowed to reset stock';
  exception when insufficient_privilege then null;
  end;

  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  result := public.reset_stall_stock(v_stall_id);
  if result ->> 'scope' <> 'stock' or (result ->> 'affected_records')::integer <> 1
    or public.get_stock_on_hand(v_stall_id, product_id) <> 0 then
    raise exception 'Stock reset did not balance the product to zero';
  end if;

  result := public.reset_stall_transactions(v_stall_id);
  if result ->> 'scope' <> 'operating_history'
    or (result ->> 'affected_records')::integer <> 1
    or (result ->> 'affected_operating_days')::integer <> 1
    or not exists (select 1 from public.transactions where id = v_transaction_id and deleted_at is not null)
    or not exists (select 1 from public.transaction_items where transaction_id = v_transaction_id and deleted_at is not null)
    or exists (select 1 from public.business_days where id = v_business_day_id)
    or exists (select 1 from public.daily_store_closings where stall_id = v_stall_id)
    or exists (select 1 from public.revenue_deductions where stall_id = v_stall_id) then
    raise exception 'Sales reset did not clear the stall operating history';
  end if;

  if (select financial_report_reset_at is null from public.stalls where id = v_stall_id)
    or (select financial_report_reset_at > now() + interval '1 minute' from public.stalls where id = v_stall_id)
    or public.get_stock_on_hand(v_stall_id, product_id) <> 0
    or (select count(*) from public.inventory_ledger where stall_id = v_stall_id and deleted_at is null) <> 2 then
    raise exception 'Financial cutoff or stock audit was not preserved by sales reset';
  end if;

  if (select count(*) from public.security_audit_log
      where security_audit_log.stall_id = v_stall_id
        and action in ('stall.stock_reset', 'stall.operating_history_reset')) <> 2 then
    raise exception 'Data reset actions were not written to the security audit log';
  end if;
  if not exists (
    select 1 from public.security_audit_log
    where security_audit_log.stall_id = v_stall_id
      and action = 'stall.operating_history_reset'
      and details ->> 'stale_open_day_count' = '1'
  ) then
    raise exception 'The stale cloud operating day was not recorded in the reset audit';
  end if;
end;
$$;

rollback;
