-- Optional product photos shared by the IMS and device-bound POS catalog.
begin;

alter table public.products add column image_url text;

-- Public reads let the POS display and cache product photos without expiring URLs.
-- Only administrators who can manage the path's stall may upload or delete photos.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('product-images', 'product-images', true, 5242880,
  array['image/jpeg', 'image/png', 'image/webp']);

create policy "administrators upload product images" on storage.objects
for insert to anon, authenticated
with check (
  bucket_id = 'product-images'
  and (storage.foldername(name))[1] in (
    select s.id::text from public.stalls s where public.can_manage_stall(s.id)
  )
);

create policy "administrators delete product images" on storage.objects
for delete to anon, authenticated
using (
  bucket_id = 'product-images'
  and (storage.foldername(name))[1] in (
    select s.id::text from public.stalls s where public.can_manage_stall(s.id)
  )
);

-- Cost-free POS catalog and an atomic, device-bound sale endpoint.

drop function public.get_pos_products(timestamptz);
create function public.get_pos_products(p_updated_after timestamptz default null)
returns table (
  id uuid, stall_id uuid, category_id uuid, category_name text, sku text, name text, unit text,
  sale_price numeric(12,2), low_stock_threshold numeric(12,3), pack_size numeric(12,3),
  conversion_rate numeric(12,3), is_sellable boolean, image_url text, updated_at timestamptz, deleted_at timestamptz
)
language plpgsql stable security definer set search_path = public as $$
begin
  if public.current_app_role() <> 'cashier' then raise exception 'CASHIER_REQUIRED' using errcode = '42501'; end if;
  if not exists (
    select 1 from public.devices d
    where d.stall_id = public.current_app_stall_id() and d.is_active and d.deleted_at is null
      and d.hardware_id = coalesce(current_setting('request.headers', true)::json ->> 'x-device-id', '')
  ) then raise exception 'ACTIVE_DEVICE_REQUIRED' using errcode = '42501'; end if;
  return query select p.id, p.stall_id, p.category_id, coalesce(c.name, 'Uncategorized'), p.sku, p.name, p.unit,
    p.sale_price, p.low_stock_threshold, p.pack_size, p.conversion_rate,
    p.is_sellable, p.image_url, p.updated_at, p.deleted_at
  from public.products p
  left join public.product_categories c on c.id = p.category_id and c.stall_id = p.stall_id and c.deleted_at is null
  where p.stall_id = public.current_app_stall_id()
    and (p_updated_after is null or p.updated_at > p_updated_after)
  order by p.updated_at, p.id;
end;
$$;

grant execute on function public.get_pos_products(timestamptz) to anon, authenticated;
commit;
