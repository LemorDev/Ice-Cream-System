begin;
set local search_path = public, extensions;

do $$
declare
  stall_id uuid := gen_random_uuid(); admin_id uuid := gen_random_uuid(); cashier_id uuid := gen_random_uuid();
  old_device uuid := gen_random_uuid(); admin_token text := encode(gen_random_bytes(32), 'hex');
  cashier_token text := encode(gen_random_bytes(32), 'hex'); activation jsonb; new_device uuid;
  lost_device uuid := gen_random_uuid(); replacement_device uuid; recovery jsonb; recovery_day uuid := gen_random_uuid();
  day_id uuid := gen_random_uuid(); business_date date := (now() at time zone 'Asia/Manila')::date;
  signed_in record;
begin
  insert into public.stalls(id, name, code) values (stall_id, 'Activation fixture', 'ACT-' || upper(substr(stall_id::text, 1, 8)));
  insert into public.app_users(id, stall_id, email, display_name, role, password_hash) values
    (admin_id, stall_id, admin_id::text || '@example.invalid', 'Admin', 'system_admin', 'unused'),
    (cashier_id, stall_id, cashier_id::text || '@example.invalid', 'Cashier', 'cashier', crypt('fixture-password', gen_salt('bf')));
  insert into public.app_sessions(user_id, token_hash, expires_at) values
    (admin_id, encode(digest(admin_token, 'sha256'), 'hex'), now() + interval '1 hour'),
    (cashier_id, encode(digest(cashier_token, 'sha256'), 'hex'), now() + interval '1 hour');
  insert into public.devices(id, stall_id, device_name, activation_code_hash, hardware_id)
    values (old_device, stall_id, 'Old POS', 'old-code-hash', 'old-hardware');

  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  begin
    perform public.create_device_activation(stall_id, 'New POS');
    raise exception 'Unprepared device was allowed to be replaced';
  exception when sqlstate '22023' then null;
  end;
  begin
    perform public.authorize_pos_recovery(stall_id, 'short');
    raise exception 'Recovery accepted an uninformative reason';
  exception when sqlstate '22023' then null;
  end;
  perform public.authorize_pos_recovery(stall_id, 'Old phone was lost after closing.');
  if (select transfer_ready_at from public.devices where id = old_device) is null then
    raise exception 'Recovery did not prepare replacement';
  end if;
  update public.devices set transfer_ready_at = null where id = old_device;

  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token, 'x-device-id', 'old-hardware')::text, true);
  perform public.prepare_pos_replacement(old_device);
  select * into signed_in from public.login_pos_with_password(
    (select code from public.stalls where id = stall_id), cashier_id::text || '@example.invalid', 'fixture-password');
  if signed_in.device_id is distinct from old_device or not signed_in.is_activated
    or (select transfer_ready_at from public.devices where id = old_device) is not null then
    raise exception 'Fresh sign-in did not restore the device and cancel preparation';
  end if;
  perform public.prepare_pos_replacement(old_device);
  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  activation := public.create_device_activation(stall_id, 'New POS');
  if (activation ->> 'expires_at')::timestamptz <= now() then raise exception 'Activation code has no expiry'; end if;
  update public.devices set activation_expires_at = now() - interval '1 second'
    where id = (activation ->> 'device_id')::uuid;
  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token, 'x-device-id', 'new-hardware')::text, true);
  begin
    perform public.activate_pos_device(activation ->> 'activation_code', 'new-hardware');
    raise exception 'Expired activation code was accepted';
  exception when sqlstate '28000' then null;
  end;
  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  activation := public.create_device_activation(stall_id, 'New POS');
  if public.revoke_pending_pos_activation(stall_id) <> 1 then raise exception 'Pending code was not revoked'; end if;
  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token, 'x-device-id', 'new-hardware')::text, true);
  begin
    perform public.activate_pos_device(activation ->> 'activation_code', 'new-hardware');
    raise exception 'Revoked activation code was accepted';
  exception when sqlstate '28000' then null;
  end;
  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  activation := public.create_device_activation(stall_id, 'New POS');
  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token, 'x-device-id', 'new-hardware')::text, true);
  begin
    perform public.activate_pos_device(activation ->> 'activation_code', 'wrong-hardware');
    raise exception 'Mismatched device header was accepted';
  exception when insufficient_privilege then null;
  end;
  new_device := (public.activate_pos_device(activation ->> 'activation_code', 'new-hardware') ->> 'device_id')::uuid;
  if (select is_active from public.devices where id = old_device) then raise exception 'Old POS remained active'; end if;

  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token, 'x-device-id', 'old-hardware')::text, true);
  begin
    perform public.push_pos_transaction(jsonb_build_object('id', gen_random_uuid(), 'stall_id', stall_id,
      'device_id', old_device, 'receipt_number', 'BLOCKED', 'total_amount', 1));
    raise exception 'Inactive POS uploaded a sale';
  exception when insufficient_privilege then null;
  end;
  begin
    perform public.push_pos_inventory_entry(jsonb_build_object('id', gen_random_uuid(), 'stall_id', stall_id,
      'product_id', gen_random_uuid(), 'quantity_delta', 1, 'movement_type', 'receive'));
    raise exception 'Inactive POS uploaded an inventory entry';
  exception when insufficient_privilege then null;
  end;

  insert into public.business_days(id, stall_id, device_id, cashier_id, business_date, opened_at)
    values (day_id, stall_id, new_device, cashier_id, business_date, now() - interval '2 hours');
  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token, 'x-device-id', 'new-hardware')::text, true);
  begin
    perform public.push_business_day(jsonb_build_object('id', gen_random_uuid(), 'stall_id', stall_id,
      'device_id', new_device, 'business_date', business_date, 'opened_at', now()));
    raise exception 'Different local operating-day ID was acknowledged as duplicate';
  exception when unique_violation then null;
  end;

  update public.devices set is_active = false where id = new_device;
  insert into public.devices(id, stall_id, device_name, activation_code_hash, hardware_id)
    values (lost_device, stall_id, 'Lost phone', 'lost-hash', 'lost-hardware');
  insert into public.business_days(id, stall_id, device_id, cashier_id, business_date, opened_at)
    values (recovery_day, stall_id, lost_device, cashier_id, business_date + 1, now() - interval '1 hour');
  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  recovery := public.authorize_pos_recovery(stall_id, 'Phone was lost during the operating day.');
  if recovery ->> 'open_business_date' <> (business_date + 1)::text then
    raise exception 'Recovery did not preserve the open operating day';
  end if;
  activation := public.create_device_activation(stall_id, 'Replacement POS');
  perform set_config('request.headers', jsonb_build_object('x-session-token', cashier_token, 'x-device-id', 'replacement-hardware')::text, true);
  recovery := public.activate_pos_device(activation ->> 'activation_code', 'replacement-hardware');
  replacement_device := (recovery ->> 'device_id')::uuid;
  if recovery #>> '{resume_day,id}' <> recovery_day::text
    or (select device_id from public.business_days where id = recovery_day) <> replacement_device
    or (select is_active from public.devices where id = lost_device) then
    raise exception 'Replacement did not take over the day and retire the lost phone';
  end if;
  recovery := public.get_recovered_pos_day(replacement_device);
  if recovery ->> 'id' <> recovery_day::text then raise exception 'Replacement cannot retrieve recovered day'; end if;
  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  begin
    perform public.review_pos_recovery((select id from public.pos_recovery_incidents where new_device_id = replacement_device),
      'Reconciled counted cash against available receipts.');
    raise exception 'Open recovered day was reviewed before closing';
  exception when sqlstate '22023' then null;
  end;
  update public.business_days set closed_at = now(), closing_cash_total = 0 where id = recovery_day;
  perform set_config('request.headers', jsonb_build_object('x-session-token', admin_token)::text, true);
  recovery := public.review_pos_recovery((select id from public.pos_recovery_incidents where new_device_id = replacement_device),
    'Reconciled counted cash against available receipts.');
  if recovery ->> 'status' <> 'reviewed' then raise exception 'Closed recovery incident did not review'; end if;
end;
$$;
rollback;
