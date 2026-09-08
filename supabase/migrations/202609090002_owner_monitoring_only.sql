-- Owners use the web IMS for read-only monitoring. System administrators retain
-- management controls, while Cashiers continue to write through device-bound RPCs.

create or replace function public.can_monitor_stall(p_stall_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.stalls s
    where s.id = p_stall_id
      and s.deleted_at is null
      and (
        public.current_app_role() = 'system_admin'
        or (
          public.current_app_role() = 'owner'
          and exists (
            select 1
            from public.owner_stall_access osa
            where osa.user_id = public.current_app_user_id()
              and osa.stall_id = p_stall_id
          )
        )
      )
  );
$$;

create or replace function public.can_manage_stall(p_stall_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select public.current_app_role() = 'system_admin'
    and exists (select 1 from public.stalls s where s.id = p_stall_id and s.deleted_at is null);
$$;

-- Compatibility name retained for older server helpers. Management is now a
-- System Administrator capability; Owner monitoring uses can_monitor_stall.
create or replace function public.is_manager()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select public.is_system_admin();
$$;

drop policy if exists "administrators view accessible stalls" on public.stalls;
drop policy if exists "management users view accessible stalls" on public.stalls;
create policy "management users view accessible stalls" on public.stalls for select
  using (public.can_monitor_stall(id));

drop policy if exists "administrators view categories" on public.product_categories;
drop policy if exists "management users view categories" on public.product_categories;
create policy "management users view categories" on public.product_categories for select
  using (public.can_monitor_stall(stall_id));

drop policy if exists "administrators view products" on public.products;
drop policy if exists "management users view products" on public.products;
create policy "management users view products" on public.products for select
  using (public.can_monitor_stall(stall_id));

drop policy if exists "authorized users view transactions" on public.transactions;
create policy "authorized users view transactions" on public.transactions for select
  using (
    public.can_monitor_stall(stall_id)
    or (public.current_app_role() = 'cashier' and stall_id = public.current_app_stall_id()
      and cashier_id = public.current_app_user_id())
  );

drop policy if exists "authorized users view transaction items" on public.transaction_items;
create policy "authorized users view transaction items" on public.transaction_items for select
  using (exists (
    select 1
    from public.transactions t
    where t.id = transaction_id
      and (
        public.can_monitor_stall(t.stall_id)
        or (public.current_app_role() = 'cashier' and t.stall_id = public.current_app_stall_id()
          and t.cashier_id = public.current_app_user_id())
      )
  ));

drop policy if exists "administrators view legacy closures" on public.daily_closures;
drop policy if exists "management users view legacy closures" on public.daily_closures;
create policy "management users view legacy closures" on public.daily_closures for select
  using (public.can_monitor_stall(stall_id));

drop policy if exists "authorized users view business days" on public.business_days;
create policy "authorized users view business days" on public.business_days for select
  using (
    public.can_monitor_stall(stall_id)
    or (public.current_app_role() = 'cashier' and stall_id = public.current_app_stall_id()
      and cashier_id = public.current_app_user_id())
  );

create or replace function public.get_my_stalls()
returns table (id uuid, name text, code text, updated_at timestamptz, overhead_config jsonb)
language sql
stable
security definer
set search_path = public
as $$
  select s.id, s.name, s.code, s.updated_at, s.overhead_config
  from public.stalls s
  where s.deleted_at is null and public.can_monitor_stall(s.id)
  order by s.name;
$$;

revoke all on function public.can_monitor_stall(uuid) from public;
grant execute on function public.can_monitor_stall(uuid) to anon, authenticated;
