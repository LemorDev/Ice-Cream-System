-- Repair the known cup classification and reject future manual stock receipts
-- or adjustments against made-to-order menu items.

update public.products
set product_type = 'packaging', is_sellable = false
where lower(trim(name)) = 'medium sundae cup'
  and product_type = 'raw'
  and deleted_at is null;

create or replace function public.enforce_stockable_inventory_entry()
returns trigger language plpgsql set search_path = public as $$
begin
  if new.movement_type in ('receive', 'adjustment') and exists (
    select 1 from public.products p
    where p.id = new.product_id and p.stall_id = new.stall_id
      and p.product_type = 'sellable'
  ) then
    raise exception 'STOCKABLE_PRODUCT_REQUIRED' using errcode = '22023';
  end if;
  return new;
end;
$$;

create trigger inventory_ledger_stockable_entry
before insert or update of product_id, movement_type, stall_id on public.inventory_ledger
for each row execute function public.enforce_stockable_inventory_entry();
