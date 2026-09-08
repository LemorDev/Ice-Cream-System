# Project review — 8 September 2026

The project has a working development foundation, but it is not ready for production or an unattended stall pilot. This review covered the React IMS, Android checkout/session/sync paths, Supabase migrations and their client contracts. Existing uncommitted work was preserved. No dependency upgrades, deployment, remote database writes, or release signing were performed.

## Focused fixes made

- **Web operations:** successful product archives no longer report a false failure; stock adjustments include nonsellable raw materials and packaging; waste reporting uses transaction references and product quantities instead of assuming one unit.
- **Stall settings:** periodic refresh preserves unsaved edits. Overhead saves now surface database failures instead of silently storing browser-only values and reporting success. The overhead migration must be applied to persist overhead settings.
- **Web session recovery:** expired sessions return to sign-in, malformed expiry dates are rejected, and workspace loading failures offer sign-out as well as retry.
- **Android checkout:** stock is deducted from the current Room record inside the checkout transaction, preserving deliveries or reductions received after the cart snapshot.
- **Android synchronization:** background workers restore saved credentials before syncing. Product and ledger changes use independent cursors. Local product, stock, ledger and cursor updates commit together. Newly downloaded products receive their opening stock once.
- **Database migration prepared:** [202609080001_secure_sale_reversals.sql](../supabase/migrations/202609080001_secure_sale_reversals.sql) permits stock-neutral waste markers, scopes sale/reversal and inventory-read helpers to the signed-in stall, and serializes duplicate reversals. Reversals require a reason and an eligible posted sale; restock restores the original ledger deduction.
- **Setup documentation:** corrected the existing Gradle wrapper instructions, APK locations, and requirement to apply every pending database migration in order.
- **Three-role RBAC:** added System admin, Owner, and Cashier boundaries, explicit multi-stall Owner assignments, secured staff/stall RPCs, audit records for account and device administration, and cost-free POS catalog access.
- **Owner iPhone experience:** made the existing responsive web IMS installable from Safari as a home-screen app, with stall switching, staff management, POS activation, and operating-day history.
- **Cashier operating days:** Android now records and syncs exact opening and closing timestamps, prevents checkout while closed, and shows the current state locally. Cloud sales require the activated device and a matching operating-day window.

These changes prevent the identified failures going forward. They do not repair stock or reporting data already affected by older behavior; reconcile existing development data before relying on it.

## Remaining work, in recommended order

### 1. Finish account-switching and non-POS integrity checks

- The secured POS sale RPC now aggregates repeated products, locks stock rows, checks products and devices against the Cashier stall, and preserves same-stall idempotency. Direct administrative data paths should still receive development-database integration tests for related-record ownership before production.
- Android product/transaction queries and sync cursors are not scoped to the signed-in stall. Signing out keeps the old local data. Define safe reauthentication/account switching without exposing another stall's data or discarding unsynced sales.

### 2. Complete reliable data retrieval and recovery

- [Web workspace loading](../apps/web-ims/src/lib/api.ts) sums a limited inventory history and loads a separately limited, unordered item list. Once older deliveries drop out, stock can be wrong; report transactions may lack their items. Use complete stock aggregation and explicitly aligned report data, accounting for server row limits.
- [Android pulls](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/remote/SupabaseApi.kt) have no pagination or stable ordering. Large first/incremental pulls can miss records. Test page boundaries, equal timestamps and overlapping manual/background synchronization before claiming reliable recovery.
- Verify expired-session recovery, permanently rejected sales, process death and app upgrades with real Room storage and a development backend. The existing sync document describes intended guarantees beyond what has been integration-tested.

### 3. Finish the actual ice cream workflows

- Recipes/BOMs are absent. Checkout currently deducts the sellable product itself, not its flavor, cone/cup, beverage or add-on ingredients. Confirm pack yields and recipes, then use the same consumption rules locally and in the backend.
- Android stock is an integer while the database permits fractional units; current pull calculations truncate fractions. Decide usable inventory units alongside recipes and conversions.
- Same-day offline void/refund is still missing from the Android workflow. Implement it after stock consumption is defined, including mandatory reasons and Waste Stock/Restock handling.
- The POS stamps the Manila date when a day is opened and does not reopen a closed day. Confirm before the pilot whether an operating day may continue past midnight or must be closed at a fixed cutoff.

### 4. Make reports trustworthy

- Dashboard/report dates are derived from UTC string prefixes while displayed dates use local time. A sale at `2026-08-04T23:30:00Z` appears as 7:30 AM on August 5 in Manila, but is counted under August 4. Use one agreed business-date rule throughout the app.
- Historical costs come from today's product records; editing costs rewrites old profit, and archiving a product removes its cost from the loaded report data. Preserve sale-time costs and recipe usage before treating COGS/profit as final.
- Overhead is charged only on dates with sales or waste in the report data. Decide and test treatment of zero-sales operating days and historical overhead changes.

### 5. Validate one stall before preparing a release

Apply all migrations to a disposable development database and run [the sale/reversal SQL checks](../supabase/tests/sale_reversals.sql) plus [the RBAC and operating-day checks](../supabase/tests/rbac_business_days.sql). Then test two stall accounts, revoked devices, concurrent sales, offline sales followed by reconnect, duplicate uploads, interrupted pulls, waste/restock, and day closing. Compare local and cloud quantities and totals after every case.

Only after those workflows pass should release signing, production configuration, backups and a supervised pilot be prepared. Gradle currently warns that Android plugin 8.8.2 was tested up to SDK 35 while this project compiles SDK 36; the toolchain was left unchanged in this pass.

## Verification

- Web production build and ESLint: passed.
- Web tests: 12 passed, including archive responses, failed overhead persistence and waste quantities.
- Browser checks: login rendered; a local mock-data stall form preserved its name/rate through repeated refreshes and saved successfully. The temporary fixture was removed. No real account or database was changed.
- Android development checks: `:app:testDevDebugUnitTest` passed all 29 tests and `:app:assembleDevDebug` built the APK. Repository tests now cover platform-role rejection, verified device activation, operating-day opening/closing, closed-stall checkout rejection, operating-day-first sync, stock preservation, and retry behavior. They use mocked DAO storage and a simulated Room transaction boundary; they do not replace real-device crash/recovery tests.
- SQL: reviewed with rollback-only regression fixtures prepared, but **not executed**. No local PostgreSQL runtime is available. This is not a verified database migration rollout.
- Physical-device checkout, real Room crash recovery, live Supabase integration and release builds were not verified during this pass.
