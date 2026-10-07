-- Cash taken from the drawer belongs to the opening operating-day ID even
-- when its timestamp is after Manila midnight.
create function public.push_revenue_deduction_v2(p_deduction jsonb)
returns jsonb language plpgsql security definer set search_path = public, private, extensions as $$
declare
  stall_value uuid := public.current_app_stall_id();
  day_value uuid := nullif(p_deduction->>'business_day_id','')::uuid;
  deduction_value uuid := nullif(p_deduction->>'id','')::uuid;
  amount_value numeric(12,2) := nullif(p_deduction->>'amount','')::numeric(12,2);
  reason_value text := nullif(trim(p_deduction->>'reason'),'');
  occurred_value timestamptz := nullif(p_deduction->>'occurred_at','')::timestamptz;
  affects_value boolean := (p_deduction->>'affects_profit')::boolean;
  day_row public.business_days%rowtype;
  existing public.revenue_deductions%rowtype;
  cash_sales numeric(12,2);
  recorded numeric(12,2);
begin
  if public.current_app_role()<>'cashier' or stall_value is null or day_value is null
    or deduction_value is null or occurred_value is null
    or nullif(p_deduction->>'stall_id','')::uuid is distinct from stall_value
    or nullif(p_deduction->>'cashier_id','')::uuid is distinct from public.current_app_user_id() then
    raise exception 'INVALID_DEDUCTION_IDENTITY' using errcode='42501';
  end if;
  if amount_value is null or amount_value<=0 or reason_value is null
    or length(reason_value)>500 or affects_value is null then
    raise exception 'INVALID_DEDUCTION' using errcode='22023';
  end if;
  select * into day_row from public.business_days d where d.id=day_value and d.stall_id=stall_value
    and d.deleted_at is null for update;
  if not found or not public.active_pos_device(day_row.device_id)
    or day_row.business_date is distinct from nullif(p_deduction->>'business_date','')::date
    or occurred_value<day_row.opened_at
    or occurred_value>coalesce(day_row.closed_at,'infinity'::timestamptz) then
    raise exception 'DEDUCTION_DAY_MISMATCH' using errcode='22023';
  end if;
  select * into existing from public.revenue_deductions where id=deduction_value;
  if found then
    if existing.stall_id is distinct from stall_value or existing.business_day_id is distinct from day_value
      or existing.amount is distinct from amount_value or existing.reason is distinct from reason_value
      or existing.cashier_id is distinct from public.current_app_user_id()
      or existing.occurred_at is distinct from occurred_value
      or existing.affects_profit is distinct from affects_value then
      raise exception 'DEDUCTION_IDEMPOTENCY_CONFLICT' using errcode='23505';
    end if;
    return jsonb_build_object('status','duplicate','deduction_id',deduction_value);
  end if;
  if exists(select 1 from public.daily_store_closings where business_day_id=day_value) then
    raise exception 'DAY_ALREADY_CLOSED_RECONCILE_DEDUCTION' using errcode='23503';
  end if;
  select coalesce(sum(case when status<>'voided' then total_amount else 0 end),0)
    into cash_sales from public.transactions where business_day_id=day_value and deleted_at is null;
  select coalesce(sum(amount),0) into recorded from public.revenue_deductions where business_day_id=day_value;
  if recorded+amount_value>cash_sales then
    raise exception 'DEDUCTION_EXCEEDS_CASH_SALES' using errcode='22023';
  end if;
  insert into public.revenue_deductions(id,stall_id,business_day_id,amount,reason,cashier_id,occurred_at,affects_profit)
  values(deduction_value,stall_value,day_value,amount_value,reason_value,
    public.current_app_user_id(),occurred_value,affects_value);
  return jsonb_build_object('status','accepted','deduction_id',deduction_value);
exception when unique_violation then
  raise exception 'DEDUCTION_IDEMPOTENCY_CONFLICT' using errcode='23505';
end;
$$;
revoke all on function public.push_revenue_deduction_v2(jsonb) from public;
grant execute on function public.push_revenue_deduction_v2(jsonb) to anon,authenticated;
