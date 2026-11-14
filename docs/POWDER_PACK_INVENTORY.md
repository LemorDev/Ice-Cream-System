# Powder pack inventory

In IMS, edit each ice cream powder product and enable **Deduct powder when opened in POS**. A tracked powder is a raw ingredient received in whole packs. Use one piece per pack for piece-based stock or 1,000g per pack for gram-based stock. Enter the reorder threshold in packs. Recipe quantities can still be used to estimate the cost per serving.

## Daily use

1. Receive powder in whole packs. Two packs add two sealed packs to stock.
2. On the POS, open **Inventory → Daily inventory → Record powder opening**, select the powder, and enter the number of packs opened. Select **Record opening** to deduct the packs and book their cost to the operating day. Daily inventory lists powder openings and stock receipts with their sync status.
3. Ring up cones and cups as usual. Checkout deducts packaging and any other automatically consumed ingredients. IMS shows powder openings separately from sales usage in daily reconciliation.

The POS saves openings offline and syncs them with stable IDs. IMS shows the new stock after sync.

## Closing stock count

In **IMS → Products**, enable **Require a physical count when the cashier closes the POS** for each stock item to audit. The migration enables this for matching existing cup and cone sizes, vanilla and ube powder, and Sprite and Coke stock records. Add missing drinks as raw stock items with **Piece** as the base unit. If a drink is sold through a menu item, link that stock item as its recipe ingredient so sales deduct it.

After the last sale, select **Inventory → Daily inventory → Count stock and close** on the POS. Enter what is physically left for each item, including zero when it has run out. For powder with pack tracking enabled, count unopened packs. Review the differences and confirm the close. The POS saves the counts and closing together. **Daily Close & Profit → Inventory reconciliation** in IMS shows the saved counts and differences after sync.

## Switch an existing stall

Close the operating day and sync every POS using the previous version. Apply the database migration and deploy the updated POS and IMS together. Prior versions still upload their original sale and opening movements, so upgrade all cashiers' devices before resuming sales.

Before resuming, physically count unopened packs. In IMS, use **Count sealed packs** and **Adjust inventory** to reconcile the sealed count and stock balance to that physical count. If the recorded stock balance is too low to accept the sealed count, correct the stock balance first. Review any downward stock correction because it is reported as an inventory adjustment. Sync the POS catalog and ledger after correcting stock.

## Move powder from grams to pieces

Use a raw product with **Piece** as its base unit, **Pack** as its purchase unit, **1** as the quantity per pack, and **Deduct powder when opened in POS** enabled. Enter the cost of one sealed pack. The POS deducts one piece each time the cashier opens one pack.

Close and sync the operating day before switching. For a product with gram-denominated ledger history, create a new piece-based product so each SKU retains one stock unit throughout its history. If a product was already switched in place and may have mixed gram and piece movements, also use a new product. If all existing movements are already in pieces, the existing piece product can be used.

Update each serving recipe to reference the piece-based product, then archive the gram product. For an equivalent recipe cost, divide the old gram quantity by 1,000. For example, 45g becomes 0.045 pack. Physically count the unopened packs and set the piece balance and sealed count in IMS. For a new product, record the count as its initial receipt. Sync the updated catalog and stock to every upgraded POS before reopening the day.
