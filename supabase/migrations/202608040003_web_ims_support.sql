-- Web IMS support fields and manager-only stall editing.
-- Run after 202608040002_stock_and_sale_helpers.sql.

alter table public.products
  add column if not exists pack_size numeric(12,3) not null default 1 check (pack_size > 0),
  add column if not exists conversion_rate numeric(12,3) not null default 1 check (conversion_rate > 0);

create policy "managers update their stall" on public.stalls
  for update
  using (id = public.current_app_stall_id() and public.is_manager())
  with check (id = public.current_app_stall_id() and public.is_manager());

grant update on public.stalls to anon, authenticated;
