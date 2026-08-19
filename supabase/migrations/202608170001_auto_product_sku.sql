-- Generate a unique, stable SKU for products created through the IMS.
-- Existing SKUs are retained and product edits do not change the SKU.

create or replace function public.assign_product_sku()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  next_sku_number integer;
begin
  if new.sku is not null and btrim(new.sku) <> '' then
    return new;
  end if;

  -- Serialize SKU allocation within a stall so simultaneous saves cannot duplicate a code.
  perform pg_advisory_xact_lock(hashtext(new.stall_id::text));

  select coalesce(max((substring(sku from '^PRD-([0-9]+)$'))::integer), 0) + 1
  into next_sku_number
  from public.products
  where stall_id = new.stall_id;

  new.sku := 'PRD-' || lpad(next_sku_number::text, 4, '0');
  return new;
end;
$$;

drop trigger if exists products_assign_sku on public.products;
create trigger products_assign_sku
before insert on public.products
for each row execute function public.assign_product_sku();
