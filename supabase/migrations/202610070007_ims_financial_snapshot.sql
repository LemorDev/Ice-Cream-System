-- One statement gives the Owner a consistent view while the POS is posting.
-- A function returning JSON avoids PostgREST's row cap and offset-page shifts.
create function public.get_ims_financial_snapshot(p_stall_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public, private, extensions as $$
declare result jsonb;
begin
  if public.current_app_role() not in ('owner','system_admin')
    or not public.can_access_stall(p_stall_id) then
    raise exception 'FORBIDDEN' using errcode='42501';
  end if;
  select jsonb_build_object(
    'stall',(select to_jsonb(s) from public.stalls s where s.id=p_stall_id and s.deleted_at is null),
    'categories',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,name,sort_order from public.product_categories
      where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'products',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,category_id,sell_category,sku,name,unit,sale_price,cost_price,
        low_stock_threshold,pack_size,conversion_rate,is_sellable,product_type,base_unit,
        updated_at,deleted_at from public.products
      where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'recipes',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,parent_product_id,ingredient_product_id,quantity,updated_at
      from public.product_recipes where stall_id=p_stall_id) x),'[]'::jsonb),
    'inventory',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,product_id,business_day_id,unit_cost,quantity_delta,movement_type,
        reason,reference_id,occurred_at from public.inventory_ledger
      where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'transactions',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,business_day_id,cogs,receipt_number,status,subtotal,total_amount,
        cash_received,change_amount,occurred_at from public.transactions
      where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'business_days',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,device_id,cashier_id,business_date,opened_at,opening_notes,
        closed_at,closing_cash_total,closing_notes,updated_at from public.business_days
      where stall_id=p_stall_id and deleted_at is null) x),'[]'::jsonb),
    'daily_store_closings',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,business_day_id,business_date,gross_sales,cogs,waste_cost,
        overhead_cost,revenue_deduction,net_profit,expected_cash,collected_cash,device_id,closed_at
      from public.daily_store_closings where stall_id=p_stall_id) x),'[]'::jsonb),
    'revenue_deductions',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,stall_id,business_day_id,amount,affects_profit,reason,cashier_id,occurred_at
      from public.revenue_deductions where stall_id=p_stall_id) x),'[]'::jsonb),
    'sale_reversals',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select id,transaction_id,stall_id,original_day_id,payout_day_id,cashier_id,
        kind,reason,restock,cash_returned,occurred_at
      from public.sale_reversals where stall_id=p_stall_id) x),'[]'::jsonb),
    'closed_day_corrections',coalesce((select jsonb_agg(to_jsonb(x) order by x.transaction_id) from (
      select transaction_id,stall_id,business_day_id,added_gross,added_cogs,reason,posted_at
      from public.closed_day_corrections where stall_id=p_stall_id) x),'[]'::jsonb),
    'transaction_items',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select ti.id,ti.transaction_id,ti.product_id,ti.product_name,ti.quantity,
        ti.unit_price,ti.line_total from public.transaction_items ti
      join public.transactions t on t.id=ti.transaction_id
      where t.stall_id=p_stall_id and t.deleted_at is null and ti.deleted_at is null) x),'[]'::jsonb),
    'sale_components',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from (
      select sc.ledger_id as id,sc.transaction_id,sc.product_id,sc.quantity,sc.cost_total
      from public.sale_components sc join public.transactions t on t.id=sc.transaction_id
      where t.stall_id=p_stall_id and t.deleted_at is null) x),'[]'::jsonb)
  ) into result;
  return result;
end;
$$;
revoke all on function public.get_ims_financial_snapshot(uuid) from public;
grant execute on function public.get_ims_financial_snapshot(uuid) to anon,authenticated;
