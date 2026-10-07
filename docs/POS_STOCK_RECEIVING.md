# Mobile POS stock receiving

Cashiers can open **Inventory > Receive Stock**, search ingredients or packaging, enter a quantity in the displayed base unit, add optional delivery notes, and confirm. The stock balance and pending receive ledger row commit together. Receiving is available before opening the operating day so deliveries can be recorded before sales begin.

Receipts save offline. Existing background sync uploads them; **More > Sync status > Sync now** can also upload queued receipts. Receipts rejected by an older server remain pending for a later retry. Cloud downloads use the same ledger identity to avoid applying the receipt twice.

## Deployment

Apply `supabase/migrations/202609300001_pos_stock_receiving.sql` to the development database before rolling out the Android build. The migration requires earlier repository migrations and permits only authenticated cashiers using an active device for their assigned stall. Only active raw ingredients and packaging accept receipts. Separate delivery IDs remain separate; resending an existing ID is idempotent.

The migration has not been applied to a remote database by this task.

## Verification

- Android command: `apps/android-pos/gradlew.bat -p apps/android-pos testDevDebugUnitTest assembleDevDebug`.
- Unit cases cover quantity validation, cashier authorization, stall scope, deleted/menu/missing products, fractional quantities, atomic rollback, and scheduler failures after commit.
- Repository integration exercises the actual receiving, sync, and catalog repositories together using in-memory mocked DAOs, a modeled transaction boundary, and a mocked API. Two receipts are uploaded and downloaded repeatedly without double-counting. This is not a real Room/PostgreSQL integration test.
- Sync regression covers offline failure, missing/outdated server RPCs, and duplicate acknowledgement after retry. Existing Android checkout, business-day, catalog, session, availability, and sync tests run with the new tests.
- Web regression command: `pnpm test` (40 passed).
- `supabase/tests/pos_stock_receiving.sql` adds rollback-only database cases for receipts, duplicate retries, separate deliveries, packaging, invalid quantities, product restrictions, stall restrictions, and device/session restrictions. Run against a disposable database after all migrations. Not executed here: no local PostgreSQL runtime is configured.
- No connected device/emulator was available for interactive UI or instrumented Room verification.

Android test HTML: `apps/android-pos/app/build/reports/tests/testDevDebugUnitTest/index.html`.

Final Android result: 71 tests passed, zero failures/errors; debug APK build succeeded. Web result: 40 passed, zero failures.

## September 30 enum error correction

The initial receiving migration extracted `movement_type` from JSON as text, but `inventory_ledger.movement_type` is a PostgreSQL enum. PostgreSQL rejected the insert with SQLSTATE 42804; the sale duplicate comparison also required an explicit enum cast.

For databases that already applied the receiving migration, run **`supabase/migrations/202609300002_fix_pos_inventory_movement_enum.sql`** in the Supabase SQL Editor for the same project used by the POS. It replaces the RPC with explicit enum casts in both locations. It requires no APK update.

Then tap **More > Sync status > Sync now** in the POS. The current receiving build keeps rejected receipt rows pending, so the original receipt is retried with the original ID. Do not enter that delivery again. After a successful upload, refresh the IMS inventory view.

Verification for this correction:

- Added `supabase/tests/pos_stock_receiving.integration.mjs`, using PGlite 0.5.8 (an in-memory PostgreSQL runtime). It applies the repository migrations and reproduces the original 42804 error before installing the correction.
- The corrected SQL receiving fixture passes, including raw and packaging receipts, repeated receipt IDs, distinct deliveries, input validation, stall/session/device restrictions, and existing sale insert/duplicate behavior.
- Database regression fixtures `stockable_product_classification.sql` and `set_stock_on_hand.sql` pass.
- PGlite lacks pgcrypto here; the harness uses native PostgreSQL SHA-256 for test token hashing and UUID bytes for fixture token generation. Password-login and pgcrypto behavior are outside this test. No remote database was modified.
- An additional run of the existing `recipes_and_closings.sql` fixture failed its recipe-backflush assertion. That broader fixture is available with `--extended`; it is outside the two enum-cast changes and is not counted as passed.

Run from the repository root:

```powershell
npm install --prefix apps/android-pos/app/build/sql-test-runtime --no-audit --no-fund --package-lock=false @electric-sql/pglite@0.5.8
node supabase/tests/pos_stock_receiving.integration.mjs
```

## Receiving screen confirmations and sync

The receiving screen now includes Sync to IMS, the sync status, and upload/error feedback. Enter quantities and notes, tap Review receipt, review the item and before/after stock, then tap Confirm and receive to save. Go back returns to editing without saving. A Stock received dialog confirms a successful local save. Saving and sync buttons are disabled during an active operation, and repeated sync taps are guarded in the view model.

Verification after the receiving screen update: Android regression suite passed all 71 tests with zero failures/errors, and assembleDevDebug succeeded. Device UI testing remains unverified without a connected device/emulator.
