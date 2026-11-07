# IMS product categories for POS Sell

The IMS product form now has a **POS category** field for sellable menu items. It is separate from **Flavor**. Staff can choose the suggested Cup or Cone names, reuse an existing category, or type another name such as Sundae. The product catalog shows the saved POS category under each sellable product.

Apply `supabase/migrations/202610010001_pos_sell_categories.sql` to the target Supabase project before publishing this IMS update. The migration adds `products.sell_category`, stores it through `save_product_with_recipe`, and makes `get_pos_products` return it as the existing `category_name` field. The Android POS already reads that field and builds its Sell category filters from it, so its local database and app do not need a new version. Older products without a POS category continue to use their Flavor name as the POS category until edited.

The IMS checks for the new column before saving a sellable item and displays an error instead of falsely reporting that its POS category was saved when a server has not received the migration. Existing pricing edits continue to work during the rollout.

After migration and web deployment, create or edit a sellable menu item with a POS category, save it, then use **More → Sync status → Sync now** on the POS. The category appears in Sell after sync. Cup and Cone are also special serving filters in the POS; their membership follows recipe packaging when a matching cup or cone ingredient exists. Keep the chosen category consistent with the recipe packaging for those items.

Verification: IMS build and lint pass; 42 web tests pass, including checks that Flavor and POS category travel separately in the save request and that an unmigrated server blocks the mutation. The migration has not been executed against a live Supabase project in this workspace.
