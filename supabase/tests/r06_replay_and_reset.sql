-- Disposable PostgreSQL/PGlite only. Apply all migrations, then roll back.
begin;
set local search_path = public, private, extensions;
do $$
declare
  stall_id uuid := gen_random_uuid();
  cashier_id uuid := gen_random_uuid();
  device_id uuid := gen_random_uuid();
  product_id uuid := gen_random_uuid();
  day_id uuid := gen_random_uuid();
  sale_id uuid := gen_random_uuid();
  item_id uuid := gen_random_uuid();
  token text := encode(gen_random_bytes(32), 'hex');
  payload jsonb;
  reply jsonb;
begin
  insert into public.stalls(id,name,code) values(stall_id,'R06 fixture','R06-'||substr(stall_id::text,1,8));
  insert into public.app_users(id,stall_id,email,display_name,role,password_hash)
  values(cashier_id,stall_id,cashier_id::text||'@example.invalid','Cashier','cashier',crypt('old-password',gen_salt('bf')));
  insert into public.app_sessions(user_id,token_hash,expires_at)
  values(cashier_id,encode(digest(token,'sha256'),'hex'),now()+interval '1 hour');
  insert into public.devices(id,stall_id,device_name,activation_code_hash,hardware_id)
  values(device_id,stall_id,'R06 POS','unused','r06-device');
  insert into public.products(id,stall_id,sku,name,unit,is_sellable,product_type,base_unit)
  values(product_id,stall_id,'R06-PRODUCT','Test product','piece',true,'sellable','piece');
  insert into public.inventory_ledger(stall_id,product_id,quantity_delta,movement_type)
  values(stall_id,product_id,10,'opening_balance');
  insert into public.business_days(id,stall_id,device_id,cashier_id,business_date,opened_at)
  values(day_id,stall_id,device_id,cashier_id,(now() at time zone 'Asia/Manila')::date,now()-interval '1 hour');
  perform set_config('request.headers',jsonb_build_object('x-session-token',token,'x-device-id','r06-device')::text,true);
  payload := jsonb_build_object('id',sale_id,'stall_id',stall_id,'device_id',device_id,
    'receipt_number','LOCAL-'||sale_id::text,'status','completed','subtotal',100,'total_amount',100,
    'cash_received',100,'change_amount',0,'occurred_at',now(),
    'items',jsonb_build_array(jsonb_build_object('id',item_id,'product_id',product_id,
      'product_name','Test product','quantity',1,'unit_price',100,'line_total',100)));
  reply := public.push_pos_transaction(payload);
  if reply->>'status' <> 'accepted' then raise exception 'First sale rejected: %',reply; end if;
  reply := public.push_pos_transaction(payload);
  if reply->>'status' <> 'duplicate' then raise exception 'Identical retry rejected'; end if;
  begin
    perform public.push_pos_transaction(payload || jsonb_build_object('id',gen_random_uuid()));
    raise exception 'Receipt collision was silently accepted';
  exception when sqlstate '23505' then null; end;
  begin
    perform public.push_pos_transaction(payload || jsonb_build_object('items',
      jsonb_build_array(jsonb_build_object('id',item_id,'product_id',product_id,
      'product_name','Test product','quantity',1,'unit_price',100,'line_total',100,'comment','changed'))));
    raise exception 'Changed payload was silently accepted';
  exception when sqlstate '23505' then null; end;
  if (select count(*) from public.transactions t where t.id=sale_id) <> 1
     or public.get_stock_on_hand(stall_id,product_id) <> 9 then
    raise exception 'Replay changed sale or stock';
  end if;

  -- A managed password change must invalidate a previously valid session
  -- and release a locked account for the verified user.
  insert into private.password_login_failures(user_id,failed_count,window_started_at,blocked_until)
  values(cashier_id,5,now(),now()+interval '15 minutes');
  update public.app_users set password_hash=crypt('new-password',gen_salt('bf')) where id=cashier_id;
  if exists(select 1 from public.app_sessions where user_id=cashier_id and revoked_at is null)
     or exists(select 1 from private.password_login_failures where user_id=cashier_id) then
    raise exception 'Password reset left active session or cooldown';
  end if;
  if public.current_app_user_id() is not null then raise exception 'Revoked token still authorizes'; end if;
end;
$$;
rollback;
