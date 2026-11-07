-- The first-release POS uses the day-bound, snapshotted RPCs. Older clients
-- must retain their local queue and upgrade instead of posting incomplete
-- sales, cash movements or closings into the release database.
revoke all on function public.push_pos_transaction(jsonb) from public,anon,authenticated;
revoke all on function public.push_daily_store_closing(jsonb) from public,anon,authenticated;
revoke all on function public.push_revenue_deduction(jsonb) from public,anon,authenticated;
revoke all on function public.push_pos_inventory_entry(jsonb) from public,anon,authenticated;
revoke all on function public.post_sale_inventory_ledger(uuid,text) from public,anon,authenticated;
