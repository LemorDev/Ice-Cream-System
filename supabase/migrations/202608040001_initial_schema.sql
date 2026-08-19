-- Ice Cream POS: initial schema, custom-password sessions, and Row-Level Security.
-- Run this file once in the Supabase SQL Editor as the project database owner.

create extension if not exists pgcrypto;

create type public.app_role as enum ('manager', 'cashier');
create type public.inventory_movement_type as enum ('receive', 'sale', 'void_restock', 'void_waste', 'adjustment', 'opening_balance');
create type public.transaction_status as enum ('completed', 'voided', 'refunded');

create table public.stalls (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  code text not null unique,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table public.app_users (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  email text not null unique check (email = lower(email)),
  display_name text not null,
  role public.app_role not null default 'cashier',
  password_hash text not null,
  is_active boolean not null default true,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table public.app_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.app_users(id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  revoked_at timestamptz
);

create table public.devices (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  device_name text not null,
  activation_code_hash text not null unique,
  is_active boolean not null default true,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table public.product_categories (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  name text not null,
  sort_order integer not null default 0,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (stall_id, name)
);

create table public.products (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  category_id uuid references public.product_categories(id),
  sku text not null,
  name text not null,
  unit text not null,
  sale_price numeric(12,2) not null default 0 check (sale_price >= 0),
  cost_price numeric(12,2) not null default 0 check (cost_price >= 0),
  low_stock_threshold numeric(12,3) not null default 0 check (low_stock_threshold >= 0),
  is_sellable boolean not null default true,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (stall_id, sku)
);

create table public.inventory_ledger (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  product_id uuid not null references public.products(id),
  quantity_delta numeric(12,3) not null check (quantity_delta <> 0),
  movement_type public.inventory_movement_type not null,
  reason text,
  reference_id uuid,
  occurred_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table public.transactions (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  device_id uuid references public.devices(id),
  cashier_id uuid references public.app_users(id),
  receipt_number text not null,
  status public.transaction_status not null default 'completed',
  subtotal numeric(12,2) not null check (subtotal >= 0),
  total_amount numeric(12,2) not null check (total_amount >= 0),
  cash_received numeric(12,2) check (cash_received >= 0),
  change_amount numeric(12,2) check (change_amount >= 0),
  occurred_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (stall_id, receipt_number)
);

create table public.transaction_items (
  id uuid primary key default gen_random_uuid(),
  transaction_id uuid not null references public.transactions(id) on delete cascade,
  product_id uuid references public.products(id),
  product_name text not null,
  quantity numeric(12,3) not null check (quantity > 0),
  unit_price numeric(12,2) not null check (unit_price >= 0),
  line_total numeric(12,2) not null check (line_total >= 0),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table public.daily_closures (
  id uuid primary key default gen_random_uuid(),
  stall_id uuid not null references public.stalls(id),
  closed_by uuid references public.app_users(id),
  business_date date not null,
  cash_total numeric(12,2) not null default 0 check (cash_total >= 0),
  notes text,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (stall_id, business_date)
);

create index inventory_ledger_stall_product_occurred_idx on public.inventory_ledger (stall_id, product_id, occurred_at desc);
create index transactions_stall_occurred_idx on public.transactions (stall_id, occurred_at desc);
create index products_stall_updated_idx on public.products (stall_id, updated_at desc);

create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create trigger stalls_set_updated_at before update on public.stalls for each row execute function public.set_updated_at();
create trigger users_set_updated_at before update on public.app_users for each row execute function public.set_updated_at();
create trigger devices_set_updated_at before update on public.devices for each row execute function public.set_updated_at();
create trigger categories_set_updated_at before update on public.product_categories for each row execute function public.set_updated_at();
create trigger products_set_updated_at before update on public.products for each row execute function public.set_updated_at();
create trigger inventory_set_updated_at before update on public.inventory_ledger for each row execute function public.set_updated_at();
create trigger transactions_set_updated_at before update on public.transactions for each row execute function public.set_updated_at();
create trigger transaction_items_set_updated_at before update on public.transaction_items for each row execute function public.set_updated_at();
create trigger daily_closures_set_updated_at before update on public.daily_closures for each row execute function public.set_updated_at();

-- Reads the caller's custom token from the PostgREST request headers.
create or replace function public.current_app_user_id()
returns uuid
language sql stable security definer set search_path = public, extensions as $$
  select s.user_id
  from public.app_sessions s
  join public.app_users u on u.id = s.user_id
  where s.token_hash = encode(digest(coalesce(current_setting('request.headers', true)::json ->> 'x-session-token', ''), 'sha256'), 'hex')
    and s.expires_at > now()
    and s.revoked_at is null
    and u.is_active
    and u.deleted_at is null
  limit 1;
$$;

create or replace function public.current_app_stall_id()
returns uuid
language sql stable security definer set search_path = public as $$
  select stall_id from public.app_users where id = public.current_app_user_id();
$$;

create or replace function public.current_app_role()
returns public.app_role
language sql stable security definer set search_path = public as $$
  select role from public.app_users where id = public.current_app_user_id();
$$;

create or replace function public.is_manager()
returns boolean language sql stable security definer set search_path = public as $$
  select public.current_app_role() = 'manager';
$$;

create or replace function public.get_stock_on_hand(p_stall_id uuid, p_product_id uuid)
returns numeric(12,3)
language sql stable security definer set search_path = public as $$
  select coalesce(sum(il.quantity_delta), 0)::numeric(12,3)
  from public.inventory_ledger il
  where il.stall_id = p_stall_id
    and il.product_id = p_product_id
    and il.deleted_at is null;
$$;

create or replace function public.get_stock_on_hand(p_product_id uuid)
returns numeric(12,3)
language sql stable security definer set search_path = public as $$
  select public.get_stock_on_hand(public.current_app_stall_id(), p_product_id);
$$;

create or replace function public.get_low_stock_threshold(p_product_id uuid)
returns numeric(12,3)
language sql stable security definer set search_path = public as $$
  select coalesce(p.low_stock_threshold, 0)::numeric(12,3)
  from public.products p
  where p.id = p_product_id
    and p.deleted_at is null;
$$;

create or replace function public.is_low_stock(p_product_id uuid)
returns boolean
language sql stable security definer set search_path = public as $$
  select public.get_stock_on_hand(p_product_id) <= public.get_low_stock_threshold(p_product_id);
$$;

create or replace function public.post_sale_inventory_ledger(
  p_transaction_id uuid,
  p_reason text default 'sale'
)
returns integer
language plpgsql security definer set search_path = public, extensions as $$
declare
  sale_tx public.transactions%rowtype;
  inserted_rows integer := 0;
begin
  select * into sale_tx
  from public.transactions
  where id = p_transaction_id
    and deleted_at is null;

  if not found then
    raise exception 'Transaction not found' using errcode = 'P0002';
  end if;

  if sale_tx.status <> 'completed' then
    raise exception 'Only completed sales can be posted';
  end if;

  if exists (
    select 1
    from public.inventory_ledger il
    where il.reference_id = sale_tx.id
      and il.movement_type = 'sale'
      and il.deleted_at is null
  ) then
    return 0;
  end if;

  insert into public.inventory_ledger (
    stall_id,
    product_id,
    quantity_delta,
    movement_type,
    reason,
    reference_id,
    occurred_at
  )
  select
    sale_tx.stall_id,
    ti.product_id,
    -ti.quantity,
    'sale',
    coalesce(nullif(trim(p_reason), ''), 'sale'),
    sale_tx.id,
    sale_tx.occurred_at
  from public.transaction_items ti
  where ti.transaction_id = sale_tx.id
    and ti.deleted_at is null
    and ti.product_id is not null;

  get diagnostics inserted_rows = row_count;
  return inserted_rows;
end;
$$;

create or replace function public.reverse_sale_inventory_ledger(
  p_transaction_id uuid,
  p_reason text,
  p_restock boolean default false
)
returns integer
language plpgsql security definer set search_path = public, extensions as $$
declare
  sale_tx public.transactions%rowtype;
  inserted_rows integer := 0;
begin
  select * into sale_tx
  from public.transactions
  where id = p_transaction_id
    and deleted_at is null;

  if not found then
    raise exception 'Transaction not found' using errcode = 'P0002';
  end if;

  if not p_restock then
    update public.transactions
    set status = 'voided'
    where id = sale_tx.id;
    return 0;
  end if;

  if exists (
    select 1
    from public.inventory_ledger il
    where il.reference_id = sale_tx.id
      and il.movement_type = 'void_restock'
      and il.deleted_at is null
  ) then
    return 0;
  end if;

  insert into public.inventory_ledger (
    stall_id,
    product_id,
    quantity_delta,
    movement_type,
    reason,
    reference_id,
    occurred_at
  )
  select
    sale_tx.stall_id,
    ti.product_id,
    ti.quantity,
    'void_restock',
    coalesce(nullif(trim(p_reason), ''), 'sale reversal restock'),
    sale_tx.id,
    now()
  from public.transaction_items ti
  where ti.transaction_id = sale_tx.id
    and ti.deleted_at is null
    and ti.product_id is not null;

  get diagnostics inserted_rows = row_count;
  return inserted_rows;
end;
$$;

create or replace function public.login_with_password(p_email text, p_password text)
returns table (session_token text, user_id uuid, stall_id uuid, display_name text, role public.app_role, expires_at timestamptz)
language plpgsql security definer set search_path = public, extensions as $$
declare
  matched_user public.app_users%rowtype;
  new_token text := encode(gen_random_bytes(32), 'hex');
  session_expiry timestamptz := now() + interval '12 hours';
begin
  select * into matched_user from public.app_users
  where email = lower(trim(p_email)) and is_active and deleted_at is null
    and password_hash = crypt(p_password, password_hash);
  if not found then
    raise exception 'Invalid email or password' using errcode = '28000';
  end if;
  insert into public.app_sessions (user_id, token_hash, expires_at)
  values (matched_user.id, encode(digest(new_token, 'sha256'), 'hex'), session_expiry);
  return query select new_token, matched_user.id, matched_user.stall_id, matched_user.display_name, matched_user.role, session_expiry;
end;
$$;

create or replace function public.create_initial_manager(p_stall_name text, p_stall_code text, p_email text, p_password text, p_display_name text)
returns uuid language plpgsql security definer set search_path = public, extensions as $$
declare
  new_stall_id uuid;
begin
  if exists (select 1 from public.app_users) then
    raise exception 'Initial manager already created';
  end if;
  insert into public.stalls (name, code) values (p_stall_name, p_stall_code) returning id into new_stall_id;
  insert into public.app_users (stall_id, email, display_name, role, password_hash)
  values (new_stall_id, lower(trim(p_email)), p_display_name, 'manager', crypt(p_password, gen_salt('bf')));
  return new_stall_id;
end;
$$;

alter table public.stalls enable row level security;
alter table public.app_users enable row level security;
alter table public.app_sessions enable row level security;
alter table public.devices enable row level security;
alter table public.product_categories enable row level security;
alter table public.products enable row level security;
alter table public.inventory_ledger enable row level security;
alter table public.transactions enable row level security;
alter table public.transaction_items enable row level security;
alter table public.daily_closures enable row level security;

create policy "stall members can view stall" on public.stalls for select using (id = public.current_app_stall_id());
create policy "managers can view users" on public.app_users for select using (stall_id = public.current_app_stall_id() and public.is_manager());
create policy "stall members can view devices" on public.devices for select using (stall_id = public.current_app_stall_id());
create policy "managers manage devices" on public.devices for all using (stall_id = public.current_app_stall_id() and public.is_manager()) with check (stall_id = public.current_app_stall_id() and public.is_manager());

create policy "stall members view categories" on public.product_categories for select using (stall_id = public.current_app_stall_id());
create policy "managers manage categories" on public.product_categories for all using (stall_id = public.current_app_stall_id() and public.is_manager()) with check (stall_id = public.current_app_stall_id() and public.is_manager());
create policy "stall members view products" on public.products for select using (stall_id = public.current_app_stall_id());
create policy "managers manage products" on public.products for all using (stall_id = public.current_app_stall_id() and public.is_manager()) with check (stall_id = public.current_app_stall_id() and public.is_manager());
create policy "stall members view inventory" on public.inventory_ledger for select using (stall_id = public.current_app_stall_id());
create policy "managers add inventory" on public.inventory_ledger for insert with check (stall_id = public.current_app_stall_id() and public.is_manager());
create policy "stall members view transactions" on public.transactions for select using (stall_id = public.current_app_stall_id());
create policy "stall members create transactions" on public.transactions for insert with check (stall_id = public.current_app_stall_id());
create policy "managers update transactions" on public.transactions for update using (stall_id = public.current_app_stall_id() and public.is_manager()) with check (stall_id = public.current_app_stall_id() and public.is_manager());
create policy "stall members view transaction items" on public.transaction_items for select using (exists (select 1 from public.transactions t where t.id = transaction_id and t.stall_id = public.current_app_stall_id()));
create policy "stall members create transaction items" on public.transaction_items for insert with check (exists (select 1 from public.transactions t where t.id = transaction_id and t.stall_id = public.current_app_stall_id()));
create policy "stall members view daily closures" on public.daily_closures for select using (stall_id = public.current_app_stall_id());
create policy "managers manage daily closures" on public.daily_closures for all using (stall_id = public.current_app_stall_id() and public.is_manager()) with check (stall_id = public.current_app_stall_id() and public.is_manager());

revoke all on public.app_sessions from anon, authenticated;
revoke all on public.app_users from anon, authenticated;
revoke all on function public.create_initial_manager(text, text, text, text, text) from public, anon, authenticated;
grant execute on function public.login_with_password(text, text) to anon, authenticated;
grant execute on function public.current_app_user_id() to anon, authenticated;
grant execute on function public.current_app_stall_id() to anon, authenticated;
grant execute on function public.current_app_role() to anon, authenticated;
grant execute on function public.is_manager() to anon, authenticated;
grant execute on function public.get_stock_on_hand(uuid, uuid) to anon, authenticated;
grant execute on function public.get_stock_on_hand(uuid) to anon, authenticated;
grant execute on function public.get_low_stock_threshold(uuid) to anon, authenticated;
grant execute on function public.is_low_stock(uuid) to anon, authenticated;
grant execute on function public.post_sale_inventory_ledger(uuid, text) to anon, authenticated;
grant execute on function public.reverse_sale_inventory_ledger(uuid, text, boolean) to anon, authenticated;

grant select on public.stalls, public.devices, public.product_categories, public.products, public.inventory_ledger, public.transactions, public.transaction_items, public.daily_closures to anon, authenticated;
grant insert, update, delete on public.devices, public.product_categories, public.products, public.daily_closures to anon, authenticated;
grant insert on public.inventory_ledger, public.transactions, public.transaction_items to anon, authenticated;
grant update on public.transactions to anon, authenticated;

-- After the migration succeeds, uncomment this command, change the email and password,
-- then run it separately in the SQL Editor. It creates the only initial manager account.
-- select public.create_initial_manager(
--   'Main Ice Cream Stall',
--   'MAIN-001',
--   'manager@example.com',
--   'CHANGE-THIS-TO-A-STRONG-PASSWORD',
--   'Store Manager'
-- );
