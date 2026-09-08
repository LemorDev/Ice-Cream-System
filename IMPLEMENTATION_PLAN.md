# Ice Cream POS System Implementation Plan

This document is the full start-to-finish task list for building the offline Android POS app, the Supabase backend, and the web inventory dashboard.

## 1. Define Scope and Business Rules

### 1.1 Confirmed first-release scope

- [x] Build one offline-first Android POS per stall, designed for a phone form factor.
- [x] Limit each stall to one active POS device at a time; a device must be assigned to its stall during activation.
- [x] Build a web-based Inventory Management System (IMS) for administration, inventory, catalog maintenance, and profit reporting.
- [ ] Support physical cash payments only. Do not implement card, e-wallet, online payment, receipts, receipt printing, or digital receipts in v1.
- [ ] Require a successful online login and device activation to download the first catalog. After activation, sales and day closing must work offline.
- [ ] Support one continuous cashier/operator role for an entire operating day; do not implement multiple shifts in v1.

### 1.2 Confirm user roles and permissions

- [x] Configure the **Cashier / POS Operator** role to use the activated Android POS, create cash sales, cancel carts, view low-stock and sync status, and open or close the day. Same-day void/refund remains separate work.
- [x] Configure the **Owner** role to use the web IMS for assigned stalls, including dashboards, revenue, profit, staff, inventory, prices, costs, and overhead.
- [x] Configure the **System Administrator** role to use the web IMS across all stalls, manage stalls and Owner assignments, and perform Owner operations.
- [x] Ensure the POS role cannot access another stall's records, catalog, costs, or inventory.
- [ ] Decide whether cashier login is required at every app launch, and whether a PIN or biometric re-entry is required after the device has already been activated.

### 1.3 Record the product catalog and pricing

- [ ] Add raw flavor inventory: powdered Vanilla pack at PHP 4,300.00 and Ube pack at PHP 4,200.00.
- [ ] Add sellable menu items: Sundae Twist (PHP 40.00), Coffee Float (PHP 60.00), Fruit Soda Float (PHP 50.00), Sundae's Best (PHP 45.00), Soda Float (PHP 50.00), and Oreo Special (PHP 80.00).
- [ ] Add cone packaging inventory: Small Cone pack at PHP 480.00 and Large Cone pack at PHP 560.00.
- [ ] Add cup packaging inventory: Small Cup pack at PHP 110.00 and Large Cup pack at PHP 120.00.
- [ ] Add configurable add-ons: Chocolate Dip, Peanut, Sprinkle, Crushed Oreo, Marshmallow, Graham, and Syrup, initially priced at PHP 5.00 each.
- [ ] Add cone menu items: Small Cone (Ube, Vanilla, or Mixed) at PHP 20.00 and Large Cone (Ube, Vanilla, or Mixed) at PHP 25.00.
- [ ] Allow administrators to change retail prices, raw costs, packaging sizes, recipes, and add-on prices without requiring a POS app update.

### 1.4 Define inventory units, recipes, and stock thresholds

- [ ] Treat Vanilla and Ube as bulk packs, with a low-stock warning at exactly one pack remaining.
- [ ] Track cones as individual pieces, with a low-stock warning below 20 pieces.
- [ ] Track cups as individual pieces, with a low-stock warning below 15 pieces.
- [ ] Display a critical out-of-stock alert at zero quantity. Allow sales while stock is low, but do not allow stock to become negative.
- [ ] Define the pack-to-usable-unit conversion for every bulk item (for example, servings or scoops per flavor pack and pieces per cone/cup/add-on pack).
- [ ] Define a recipe/BOM for every menu item: flavor amount, cup or cone, beverage base, soda, topping, and each optional add-on consumed per sale.
- [ ] Define whether a mixed-flavor cone consumes equal portions of Vanilla and Ube, and record its exact consumption quantities.
- [ ] Define the stock unit, bulk purchase cost, and pack size for beverage bases, fruit soda, soda, toppings, and syrups; these are required for accurate COGS.

### 1.5 Define sales, cancellation, void, and refund rules

- [ ] Save every completed cash sale in the local Room database immediately, deduct the recipe quantities from local inventory, and queue the transaction with `is_synced = false`.
- [ ] Treat cart clearing before payment as a cancellation: remove the items from the active cart without changing stock or creating a transaction.
- [ ] Allow same-day post-payment voids only. Require the cashier to select a reason: Customer changed mind, Incorrect order, or Quality issue.
- [ ] Require the cashier to select **Waste Stock** or **Restock** when voiding a completed sale.
- [ ] For **Waste Stock**, record the loss in the inventory ledger and do not restore available stock.
- [ ] For **Restock**, reverse the inventory deduction and keep a complete audit trail linked to the original sale.
- [ ] Allow cash refunds with a mandatory reason. Default the stock handling to Waste Stock unless the cashier explicitly chooses Restock.
- [ ] Allow administrators to record manual stock adjustments with a mandatory reason code: Spill/Melted, Expiry, Theft, or Recount Correction.
- [ ] Decide the exact cash checkout input: whether the cashier enters cash received and the system calculates change, or simply confirms the fixed sale total.

### 1.6 Define offline and synchronization behavior

- [ ] Make Room the Android POS UI's only source of truth; the POS must not wait for a network request to show catalog, inventory, sales, or totals.
- [ ] Permit offline checkout, same-day voids/refunds, and day closing after initial activation.
- [ ] Queue all locally created sales, reversals, adjustments, and end-of-day reports for background sync.
- [ ] When connectivity returns, push unsynced records first, mark them synced only after a successful cloud response, then pull newer catalog and inventory changes.
- [ ] Show the cashier a non-blocking sync status and a clear warning for a persistent sync failure.

### 1.7 Define daily closing and profit reporting

- [ ] Provide a **Close Day** action that displays total cash collected, total voids/refunds, and estimated flavor-pack, cup, and cone usage before confirmation.
- [ ] Save the closed day report locally and queue it for cloud sync.
- [ ] Calculate daily fixed overhead as PHP 743.33: cashier pay PHP 500.00, rent PHP 200.00, electricity PHP 33.33, and water PHP 10.00.
- [ ] Calculate Total Revenue as cash collected for the day.
- [ ] Calculate COGS from the raw purchase costs and recipes of items sold.
- [ ] Calculate Waste Cost from voided/refunded items and adjustments marked as waste.
- [ ] Calculate Net Profit as `Total Revenue - COGS - Waste Cost - PHP 743.33`.
- [ ] Decide whether the administrator may reopen a closed day, who may do so, and how the correction is audited.
- [ ] Decide the operating-day cutoff time and how sales made after midnight are assigned to a business day.

### 1.8 Validate scope with acceptance criteria

- [ ] Verify that completing an offline sale deducts the exact local recipe quantities, records an unsynced transaction, and does not prompt for a receipt.
- [ ] Verify that a cone warning appears at 19 pieces and a cup warning appears at 14 pieces; flavor stock warns at one pack.
- [ ] Verify that the POS can sell down to zero stock and gives a critical alert at zero without allowing negative inventory.
- [ ] Verify that a post-payment void forces a reason and Waste Stock/Restock choice; Waste Stock does not increase available inventory.
- [ ] Verify that 15 transactions created offline are pushed automatically when Wi-Fi or mobile data becomes available, and are then marked as synced locally.
- [ ] Verify that closing a day queues its report locally and that the IMS applies COGS, waste cost, and PHP 743.33 fixed overhead after sync.
- [ ] Obtain stakeholder sign-off on this scope, catalog, recipes, costing inputs, and operational rules before beginning database design.

## 2. Set Up the Development Environment

1. [x] Create the repo structure.
2. [x] Set up the Android project.
3. [x] Set up the React web project.
4. [x] Set up the Supabase project.
5. Define local and production environments.
6. Create environment variable conventions.
7. Set up formatting and linting tools.
8. Set up CI checks.
9. Create shared documentation folders.
10. Document setup steps for all developers.

## 3. Design the Cloud Data Model

1. [x] Create the PostgreSQL schema in Supabase.
2. [x] Add core tables for users and roles.
3. [x] Add tables for stalls and device assignment.
4. [x] Add the `products` table.
5. [x] Add the `inventory_ledger` table.
6. [x] Add the `transactions` table.
7. [x] Add the `transaction_items` table.
8. [x] Add sync columns to every synced table.
9. [x] Add soft-delete support.
10. [x] Add indexes for lookup and sync performance.
11. [x] Add constraints to protect data integrity.
12. [x] Add triggers for `updated_at`.
13. [x] Add helper functions for stock calculations.
14. [x] Add helper functions for sale posting and reversal.

## 4. Configure Security and Access Control

1. [x] Implement custom password and session authentication (no external provider).
2. [x] Define role-based access rules.
3. Define stall-based data isolation.
4. [x] Configure Row-Level Security policies.
5. Verify users can only see their own stall data.
6. Verify devices can only sync their assigned stall data.
7. Prevent client apps from using elevated keys.
8. Add audit logging for important changes.
9. Document credential handling rules.

## 5. Build the Web Inventory Management System

1. [x] Scaffold the React + Vite app.
  2. [x] Add Tailwind styling.
3. [x] Build the custom Owner and System admin password sign-in flow.
  4. [x] Build the stall management screens.
  5. [x] Build the product management screens.
  6. [x] Build stock receiving screens.
  7. [x] Build inventory adjustment screens.
  8. [x] Build price and conversion management screens.
  9. [x] Build transaction history screens.
  10. [x] Build sales reporting screens.
  11. [x] Build export/download features.
  12. [x] Add loading, empty, and error states.
  13. [x] Add validation for all forms.
  14. [x] Add role-based UI access.
  15. [x] Add dashboard tests.

## 6. Build the Android App Foundation

1. Create the Kotlin app project.
2. Add Jetpack Compose.
3. Add Room.
4. Add WorkManager.
5. Add Retrofit and OkHttp.
6. Add Coroutines and Flow.
7. Add navigation structure.
8. Add dependency injection.
9. Add logging and error reporting setup.
10. Add build variants for dev and production.
11. Add the base app architecture and package structure.

## 7. Design the Local Room Database

1. [x] Mirror the cloud schema in Room entities.
2. [x] Add local-only fields for sync state.
3. [x] Add `is_synced` to transactions.
4. [x] Add local timestamps and metadata.
5. [x] Add sync cursor tracking.
6. [x] Add DAOs for reads and writes.
7. [x] Add repository classes.
8. [x] Add Room migrations.
9. [x] Add a local transaction boundary for checkout.
10. [x] Confirm the local DB is the single source of truth for the UI.

## 8. Build the Offline POS UI

1. [x] Build the login and device activation screens.
2. [x] Build the home screen.
3. [x] Build the product catalog screen.
4. [x] Build the category filter and search UI.
5. [x] Build the cart and checkout flow.
6. [x] Build payment capture screens.
7. [x] Build receipt and confirmation screens.
8. [x] Build transaction history screens.
9. [x] Build stock status indicators.
10. [x] Build sync status indicators.
11. [x] Build settings and device info screens.
12. [x] Make every screen read from Room first.
13. [x] Ensure checkout works completely offline.
14. [x] Ensure local stock is updated immediately after sale.

## 9. Implement the Backend API Contract

1. [x] Define the API endpoints needed by Android.
2. [x] Define the payloads for push sync.
3. [x] Define the payloads for pull sync.
4. [x] Define auth headers and device headers.
5. [x] Define idempotency keys for transactions.
6. [x] Define response codes and error handling rules.
7. [x] Define retryable versus non-retryable failures.
8. [x] Document the sync contract clearly.

## 10. Build the Sync Engine

1. [x] Create the WorkManager sync worker.
2. [x] Detect network availability.
3. [x] Trigger sync on startup and reconnect.
4. [x] Trigger sync after checkout.
5. [x] Push unsynced transactions first.
6. [x] Mark transactions synced only after success.
7. [x] Pull updated products and inventory data next.
8. [x] Use the latest sync cursor for incremental pulls.
9. [x] Handle duplicate submissions safely.
10. [x] Handle partial failures safely.
11. [x] Retry transient network errors.
12. [x] Surface permanent sync errors in the UI.
13. [x] Persist sync history and last sync time.

## 11. Handle Conflict and Recovery Cases

1. [x] Handle duplicate transaction submission.
2. [x] Handle power loss during checkout.
3. [x] Handle network loss during push.
4. [x] Handle network loss during pull.
5. [x] Handle server timeouts after a successful write.
6. [x] Handle remote price updates while offline.
7. [x] Handle remote stock adjustments while offline.
8. [x] Handle device revocation.
9. [x] Handle app updates and database migrations.
10. [x] Handle negative-stock prevention.
11. [x] Handle failed sync queue recovery.

## 12. Add Testing

1. Add database unit tests.
2. Add backend migration tests.
3. Add RLS policy tests.
4. Add API integration tests.
5. Add dashboard component tests.
6. Add dashboard end-to-end tests.
7. Add Android DAO tests.
8. Add Android repository tests.
9. Add offline checkout tests.
10. Add sync worker tests.
11. Add duplicate and retry tests.
12. Add device restart and recovery tests.
13. Add offline-first scenario tests.

## 13. Validate Security and Performance

1. Review all secrets handling.
2. Verify no service key is shipped to clients.
3. Verify auth tokens are stored securely.
4. Add local device protection.
5. Add PIN or biometric re-entry if needed.
6. Measure startup time on low-end devices.
7. Measure checkout speed.
8. Measure sync speed on weak networks.
9. Check database growth over time.
10. Check dashboard responsiveness.
11. Check battery usage during background sync.

## 14. Prepare Deployment

1. Set up Supabase production project.
2. Apply database migrations to production.
3. Configure production auth settings.
4. Configure production RLS policies.
5. Set up backups and recovery.
6. Deploy the web dashboard.
7. Build the Android release APK or AAB.
8. Set up app signing.
9. Define versioning and release notes.
10. Prepare internal testing tracks.

## 15. Run Pilot Deployment

1. Choose one stall for pilot testing.
2. Assign one Owner, one Cashier, and one device.
3. Load initial products and inventory.
4. Test offline sales during the pilot.
5. Test reconnect and sync after offline use.
6. Compare local totals with cloud totals.
7. Collect bugs and operator feedback.
8. Fix issues found in the pilot.
9. Re-test after fixes.

## 16. Final Handoff and Operations

1. Write the system architecture documentation.
2. Write the database schema documentation.
3. Write the sync protocol documentation.
4. Write the web dashboard user guide.
5. Write the Android POS user guide.
6. Write the troubleshooting guide.
7. Write the backup and restore guide.
8. Write the support and escalation process.
9. Train the stakeholders.
10. Prepare the final release checklist.

## Definition of Done

The project is complete when all of the following are true:

1. Owners can manage products, pricing, costs, overhead, staff, and stock for assigned stalls in the web dashboard.
2. Android devices can operate fully offline.
3. Sales are saved locally immediately.
4. Inventory is updated locally at checkout.
5. Sync resumes automatically when the network returns.
6. Cloud and local data stay consistent.
7. RLS prevents cross-stall access.
8. Failed syncs are visible and recoverable.
9. The system survives app restart and device restart.
10. The pilot stall can run the system without manual workarounds.
