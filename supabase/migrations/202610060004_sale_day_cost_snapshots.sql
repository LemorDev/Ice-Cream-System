-- First-release sale contract: the opening operating day, consumed components
-- and their booked cost travel with an offline sale. This RPC is additive so
-- the database can be migrated before the new POS binary is installed.
alter table public.transactions
  add column business_day_id uuid references public.business_days(id) on delete set null,
  add column cogs numeric(12,2) not null default 0 check (cogs >= 0);
create index transactions_business_day_idx on public.transactions(business_day_id, occurred_at, id);

alter table public.inventory_ledger
  add column business_day_id uuid references public.business_days(id) on delete set null,
  add column unit_cost numeric(18,8) check (unit_cost >= 0);
create index inventory_ledger_business_day_idx on public.inventory_ledger(business_day_id, occurred_at, id);

create table public.sale_components (
  transaction_id uuid not null references public.transactions(id) on delete cascade,
  product_id uuid not null references public.products(id),
  ledger_id uuid not null unique,
  quantity numeric(12,3) not null check (quantity > 0),
  cost_total numeric(12,2) not null check (cost_total >= 0),
  primary key(transaction_id,product_id)
);
alter table public.sale_components enable row level security;
create policy "authorized users view sale components" on public.sale_components for select
  using (exists(select 1 from public.transactions t where t.id=transaction_id
    and public.can_access_stall(t.stall_id)));
grant select on public.sale_components to anon,authenticated;

create function public.snapshot_inventory_movement()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if new.business_day_id is null then
    select day.id into new.business_day_id from public.business_days day
    where day.stall_id=new.stall_id and day.deleted_at is null
      and new.occurred_at >= day.opened_at
      and new.occurred_at <= coalesce(day.closed_at,'infinity'::timestamptz)
    order by day.opened_at desc limit 1;
  end if;
  if new.unit_cost is null then
    select case when p.pack_size*p.conversion_rate > 0
      then round(p.cost_price/(p.pack_size*p.conversion_rate),8) else 0 end
    into new.unit_cost from public.products p
    where p.id=new.product_id and p.stall_id=new.stall_id;
  end if;
  return new;
end;
$$;
create trigger inventory_ledger_book_cost_and_day before insert on public.inventory_ledger
  for each row execute function public.snapshot_inventory_movement();

-- Existing disposable-development data gets a best-effort day and cost. No
-- real production sales have occurred; these values are not UAT evidence.
update public.transactions t set business_day_id=day.id
from public.business_days day
where t.business_day_id is null and t.stall_id=day.stall_id and t.device_id=day.device_id
  and t.occurred_at>=day.opened_at and t.occurred_at<=coalesce(day.closed_at,'infinity'::timestamptz);
update public.inventory_ledger l set business_day_id=day.id
from public.business_days day
where l.business_day_id is null and l.stall_id=day.stall_id
  and l.occurred_at>=day.opened_at and l.occurred_at<=coalesce(day.closed_at,'infinity'::timestamptz);
update public.inventory_ledger l set unit_cost=case when p.pack_size*p.conversion_rate>0
  then round(p.cost_price/(p.pack_size*p.conversion_rate),8) else 0 end
from public.products p where l.unit_cost is null and l.product_id=p.id and l.stall_id=p.stall_id;

create function public.push_pos_transaction_v2(p_transaction jsonb)
returns jsonb language plpgsql security definer set search_path = public, private, extensions as $$
declare
  tx_id uuid := nullif(p_transaction->>'id','')::uuid;
  stall_id_value uuid := public.current_app_stall_id();
  device_id_value uuid := nullif(p_transaction->>'device_id','')::uuid;
  day_id_value uuid := nullif(p_transaction->>'business_day_id','')::uuid;
  receipt_value text := nullif(trim(p_transaction->>'receipt_number'),'');
  occurred_value timestamptz := nullif(p_transaction->>'occurred_at','')::timestamptz;
  gross_value numeric(12,2) := nullif(p_transaction->>'total_amount','')::numeric(12,2);
  cogs_value numeric(12,2);
  saved public.transactions%rowtype;
  upload private.pos_sale_uploads%rowtype;
  item_count integer;
  component_count integer;
begin
  if public.current_app_role()<>'cashier' then raise exception 'CASHIER_REQUIRED' using errcode='42501'; end if;
  if tx_id is null or stall_id_value is null or device_id_value is null or day_id_value is null
    or receipt_value is null or occurred_value is null
    or nullif(p_transaction->>'stall_id','')::uuid is distinct from stall_id_value then
    raise exception 'INVALID_TRANSACTION_IDENTITY' using errcode='22023';
  end if;
  if not public.active_pos_device(device_id_value) then
    raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode='42501';
  end if;
  if not exists(select 1 from public.business_days day where day.id=day_id_value
    and day.stall_id=stall_id_value and day.device_id=device_id_value and day.deleted_at is null
    and occurred_value>=day.opened_at and occurred_value<=coalesce(day.closed_at,'infinity'::timestamptz)) then
    raise exception 'OPERATING_DAY_MISMATCH' using errcode='22023';
  end if;

  select * into saved from public.transactions t where t.id=tx_id
    or (t.stall_id=stall_id_value and t.receipt_number=receipt_value)
    order by (t.id=tx_id) desc limit 1;
  if found then
    select * into upload from private.pos_sale_uploads u where u.transaction_id=saved.id;
    if saved.id<>tx_id or saved.stall_id<>stall_id_value
      or saved.receipt_number<>receipt_value or upload.payload is distinct from p_transaction then
      raise exception 'IDEMPOTENCY_CONFLICT' using errcode='23505';
    end if;
    return jsonb_build_object('status','duplicate','transaction_id',tx_id,
      'receipt_number',receipt_value,'inserted_items',0,'inserted_ledger',0);
  end if;

  if coalesce(p_transaction->>'status','completed')<>'completed'
    or jsonb_typeof(p_transaction->'items') is distinct from 'array'
    or jsonb_typeof(p_transaction->'components') is distinct from 'array' then
    raise exception 'SALE_LINES_REQUIRED' using errcode='22023';
  end if;
  if jsonb_array_length(p_transaction->'items')=0
    or jsonb_array_length(p_transaction->'components')=0 then
    raise exception 'SALE_LINES_REQUIRED' using errcode='22023';
  end if;
  if gross_value is null or gross_value<0
    or nullif(p_transaction->>'subtotal','')::numeric is distinct from gross_value
    or nullif(p_transaction->>'cash_received','') is null
    or nullif(p_transaction->>'change_amount','') is null
    or nullif(p_transaction->>'cash_received','')::numeric < gross_value
    or nullif(p_transaction->>'change_amount','')::numeric
      is distinct from nullif(p_transaction->>'cash_received','')::numeric-gross_value
    or gross_value is distinct from (select sum((item->>'line_total')::numeric)
      from jsonb_array_elements(p_transaction->'items') item)
    or exists(select 1 from jsonb_array_elements(p_transaction->'items') item
      where nullif(item->>'id','') is null or nullif(item->>'product_id','') is null
        or nullif(trim(item->>'product_name'),'') is null
        or coalesce((item->>'quantity')::numeric,0)<=0
        or coalesce((item->>'unit_price')::numeric,-1)<0
        or (item->>'line_total')::numeric is distinct from
          round((item->>'quantity')::numeric*(item->>'unit_price')::numeric,2)) then
    raise exception 'TRANSACTION_TOTAL_MISMATCH' using errcode='22023';
  end if;
  if exists(select 1 from jsonb_array_elements(p_transaction->'components') component
    where nullif(component->>'id','') is null or nullif(component->>'product_id','') is null
      or coalesce((component->>'quantity')::numeric,0)<=0
      or coalesce((component->>'cost_total')::numeric,-1)<0)
    or (select count(distinct component->>'product_id') from jsonb_array_elements(p_transaction->'components') component)
      <> jsonb_array_length(p_transaction->'components') then
    raise exception 'INVALID_SALE_COMPONENT' using errcode='22023';
  end if;
  if exists(select 1 from jsonb_array_elements(p_transaction->'items') item
    left join public.products p on p.id=(item->>'product_id')::uuid and p.stall_id=stall_id_value
    where p.id is null or not p.is_sellable or (p.deleted_at is not null and p.deleted_at<=occurred_value)) then
    raise exception 'INVALID_PRODUCT' using errcode='22023';
  end if;
  if exists(select 1 from jsonb_array_elements(p_transaction->'components') component
    left join public.products p on p.id=(component->>'product_id')::uuid and p.stall_id=stall_id_value
    where p.id is null or (p.deleted_at is not null and p.deleted_at<=occurred_value)) then
    raise exception 'INVALID_SALE_COMPONENT' using errcode='22023';
  end if;

  -- Serialize sales consuming the same ingredient and check the snapshot,
  -- rather than the recipe currently configured when an offline sale arrives.
  perform 1 from public.products p
  join (select distinct (component->>'product_id')::uuid product_id
    from jsonb_array_elements(p_transaction->'components') component) used on used.product_id=p.id
  order by p.id for update of p;
  if exists(select 1 from jsonb_array_elements(p_transaction->'components') component
    where public.get_stock_on_hand(stall_id_value,(component->>'product_id')::uuid)
      < (component->>'quantity')::numeric) then
    raise exception 'INSUFFICIENT_STOCK' using errcode='P0001';
  end if;
  select sum((component->>'cost_total')::numeric(12,2)) into cogs_value
  from jsonb_array_elements(p_transaction->'components') component;
  insert into public.transactions(id,stall_id,device_id,cashier_id,business_day_id,receipt_number,status,
    subtotal,total_amount,cash_received,change_amount,cogs,occurred_at)
  values(tx_id,stall_id_value,device_id_value,public.current_app_user_id(),day_id_value,receipt_value,'completed',
    gross_value,gross_value,(p_transaction->>'cash_received')::numeric(12,2),
    (p_transaction->>'change_amount')::numeric(12,2),cogs_value,occurred_value);
  insert into public.transaction_items(id,transaction_id,product_id,product_name,quantity,unit_price,line_total)
  select (item->>'id')::uuid,tx_id,(item->>'product_id')::uuid,item->>'product_name',
    (item->>'quantity')::numeric(12,3),(item->>'unit_price')::numeric(12,2),
    (item->>'line_total')::numeric(12,2) from jsonb_array_elements(p_transaction->'items') item;
  get diagnostics item_count=row_count;
  insert into public.inventory_ledger(id,stall_id,business_day_id,product_id,quantity_delta,movement_type,
    reason,reference_id,occurred_at,unit_cost)
  select (component->>'id')::uuid,stall_id_value,day_id_value,(component->>'product_id')::uuid,
    -(component->>'quantity')::numeric(12,3),'sale','offline checkout',tx_id,occurred_value,
    round((component->>'cost_total')::numeric/(component->>'quantity')::numeric,8)
  from jsonb_array_elements(p_transaction->'components') component;
  get diagnostics component_count=row_count;
  insert into public.sale_components(transaction_id,product_id,ledger_id,quantity,cost_total)
  select tx_id,(component->>'product_id')::uuid,(component->>'id')::uuid,
    (component->>'quantity')::numeric(12,3),(component->>'cost_total')::numeric(12,2)
  from jsonb_array_elements(p_transaction->'components') component;
  insert into private.pos_sale_uploads(transaction_id,stall_id,payload)
  values(tx_id,stall_id_value,p_transaction);
  return jsonb_build_object('status','accepted','transaction_id',tx_id,
    'receipt_number',receipt_value,'inserted_items',item_count,'inserted_ledger',component_count);
exception when unique_violation then
  select * into saved from public.transactions t where t.id=tx_id
    or (t.stall_id=stall_id_value and t.receipt_number=receipt_value)
    order by (t.id=tx_id) desc limit 1;
  select * into upload from private.pos_sale_uploads u where u.transaction_id=saved.id;
  if saved.id=tx_id and saved.stall_id=stall_id_value
    and saved.receipt_number=receipt_value and upload.payload=p_transaction then
    return jsonb_build_object('status','duplicate','transaction_id',tx_id,
      'receipt_number',receipt_value,'inserted_items',0,'inserted_ledger',0);
  end if;
  raise exception 'IDEMPOTENCY_CONFLICT' using errcode='23505';
end;
$$;
revoke all on function public.push_pos_transaction_v2(jsonb) from public;
grant execute on function public.push_pos_transaction_v2(jsonb) to anon,authenticated;
