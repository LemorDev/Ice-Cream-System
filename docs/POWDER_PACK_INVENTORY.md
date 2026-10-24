# Powder pack inventory

Tracked powder is a raw ingredient measured in grams. Its purchase unit is one sealed **Pack = 1,000g**. Enable **Track sealed powder packs and hopper grams** in the IMS product form, then enter the low-stock threshold in packs. The threshold is saved in grams and compares with **total** remaining powder, so opening a pack does not change the purchasing alert.

## Start tracking existing powder

Existing stock is initially treated as operational powder because its sealed state cannot be inferred from historical gram movements. After enabling tracking, use **Count sealed packs** in the IMS product catalog to record the physical sealed count. This reclassifies stock without changing total grams. The count cannot exceed the whole packs represented by total on-hand grams. Sync the POS catalog and ledger afterward.

## Daily use

1. Receive tracked powder in whole packs. A receipt of two packs adds 2,000g to total stock and two sealed packs.
2. On the Android POS Inventory screen, choose **Open Powder Pack**, select the powder, and enter the number of packs opened (default one). The opening reduces sealed packs and increases hopper grams by 1,000g per pack. Total stock and cost do not change.
3. A sale consumes its recipe's grams from total and operational stock. If recorded hopper grams fall below zero while total powder remains, the sale can continue and Inventory shows a reminder to record an opened pack. Sales remain blocked when total powder is insufficient.

The POS records receipts, openings, and sales locally first, then syncs them with stable IDs. An opening belongs to the operating day on the device that recorded it. The IMS sealed-pack count can be used to reconcile missed openings after the POS syncs.
