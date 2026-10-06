# R05 — release risk audit

Reviewed 6 October 2026 against source commit `3cf4158` on
`codex/inventory-recipes-ui`, the accepted [v1 scope](FIRST_RELEASE_SCOPE.md),
the dated [project review](PROJECT_REVIEW.md), and the
[release guide](RELEASE_AND_BRANCHING_PLAN.md). The only pre-audit untracked
file was `Screenshot_1790765438.png`; it is unrelated and remains untouched.

**Status meanings:** `fixed` means the dated code defect has been removed but
still needs environment acceptance; `tested` means the cited automated test
passed for the stated case; `release blocker` means v1 cannot be accepted until
the follow-up is repaired and verified; `accepted limitation` means the written
one-stall scope permits it with an operating procedure. A passing mock or
PGlite test is not a live Supabase or physical-phone test.

## Current findings

| ID | Finding and current evidence | Status | Follow-up |
| --- | --- | --- | --- |
| R05-01 | Server role boundaries were revised after the September review: [Owner monitoring and administrator management](../supabase/migrations/202609090002_owner_monitoring_only.sql) split read access from writes; [POS RPCs](../supabase/migrations/202609080004_pos_rbac_contract.sql) check Cashier, stall, active device, day window, items, and totals. [RBAC fixture](../supabase/tests/rbac_business_days.sql) covers two assigned stalls and a third unassigned stall, but is not in the passing isolated CI set; direct admin related-record ownership still lacks a development-backend integration run. | **fixed** | R08: run the full RBAC and sale/reversal fixtures on disposable PostgreSQL and probe cross-stall reads/writes through the development API. |
| R05-02 | Android [sign-out](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/SessionRepository.kt) removes only the session. [Product](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/local/dao/ProductDao.kt) and [transaction](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/local/dao/TransactionDao.kt) observers are not scoped to the current stall, and [sign-in](../apps/android-pos/app/src/main/java/com/icecreampost/pos/ui/PosViewModel.kt) immediately syncs after a new account signs in. Old local sales, stock and queue can remain on a phone used for another stall. | **release blocker** | R06: enforce a single-stall device/account rule or isolate Room data by stall; reject switching while any old queue exists. Test two-stall sign-out/sign-in and display/sync. |
| R05-03 | The [password migration](../supabase/migrations/202610050001_limit_password_login_attempts.sql) shares a private counter between web and POS, denies sign-in after five wrong passwords for 15 minutes, and avoids raising an exception that would roll back the counter. The [login fixture](../supabase/tests/password_login_limits.sql) passed in PGlite on 6 October. This migration has not been confirmed applied in development or production. | **tested** | R08: apply and verify against development Supabase before online UAT; apply to production before public use. |
| R05-04 | A System administrator can set a managed user's password through [`save_managed_user`](../supabase/migrations/202609080003_rbac_business_days.sql), but there is no verified recovery runbook. Password changes do not revoke existing `app_sessions`, and a locked account must still wait out the cooldown. | **release blocker** | R06/R18: define identity verification, reset, session revocation, cooldown handling, support owner, and a rehearsal. |
| R05-05 | [Web workspace loading](../apps/web-ims/src/lib/api.ts) still caps inventory at 5,000, sales at 1,000, items and deductions at 5,000, and days/closings at 365. Items are unordered and not tied to the fetched sale page. Stock, Owner reports, CSV and receipts can become incomplete before the server's row cap is reached. | **release blocker** | R06: use complete server stock aggregation and stable, aligned pagination or server-side report queries; test beyond each page boundary. |
| R05-06 | [Android pulls](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/remote/SupabaseApi.kt) return one list per products, recipes and ledger request. [Cursors](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/ProductRepository.kt) use strict `updated_at > cursor`, with no page token, ID tie-breaker or overlap. Equal timestamps and large pulls can be skipped. Independent product/ledger cursors and one Room transaction are improvements, but do not solve page loss. | **release blocker** | R06: page with a stable `(updated_at,id)` cursor or overlap/dedupe contract; test row limits, equal timestamps and interrupted pages. |
| R05-07 | [`getBusinessDateKey`](../apps/web-ims/src/lib/dashboard.ts) now converts UTC instants to Manila dates; [dashboard test](../apps/web-ims/src/lib/dashboard.test.ts) checks `2026-08-04T23:30:00Z` as August 5. Zero-sales operating days are included in overhead via `operatingDates` and covered by the same tests. This fixes the old UTC-prefix claim for those functions. | **tested** | Retain these regression cases in R06 date changes. |
| R05-08 | The [IMS overview](../apps/web-ims/src/screens.tsx) still chooses “today” with `toISOString().slice(0,10)` (UTC). More substantially, [report grouping](../apps/web-ims/src/lib/dashboard.ts) and [server closings/deduction gross](../supabase/migrations/202609290003_separate_revenue_deductions.sql) group sales by Manila **calendar** date, while [Android](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/BusinessDayRepository.kt) keeps the opening business date until close. A sale after midnight can move to a different report/day and change close or deduction totals. | **release blocker** | R06: assign sale, waste, refund and deductions by operating-day ID and update POS, RPCs, IMS and tests for a before/after-midnight shift. |
| R05-09 | [IMS COGS](../apps/web-ims/src/lib/dashboard.ts), [Android close COGS](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/local/dao/TransactionDao.kt), and [server closing COGS](../supabase/migrations/202609290003_separate_revenue_deductions.sql) use the current product cost. Changing or archiving a product can rewrite or erase historical profit. Stored closing totals preserve overhead for closed days, but the detailed report still recalculates COGS. | **release blocker** | R06: persist sale-time recipe component quantities and costs, then use those snapshots consistently in close, reports and refunds. Test cost edits and archives after a sale. |
| R05-10 | Recipes and fractional base units now exist in [Room checkout](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/CheckoutRepository.kt), [product pull](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/ProductRepository.kt), [server backflush](../supabase/migrations/202609110001_recipes_and_daily_closings.sql), and [unit conversion tests](../apps/web-ims/src/lib/inventory-units.test.ts). The extended PGlite recipe fixture passed after its test-order fix below. The September claim that recipes are absent or stock is truncated to integers is stale. | **tested** | R08/R09: verify the approved real menu, fractions and physical stock on development backend/phone. |
| R05-11 | Offline checkout stores ingredient usage from the phone's recipe, but the server [backflush](../supabase/migrations/202609110001_recipes_and_daily_closings.sql) uses the **current** recipe when the sale is uploaded. An administrator recipe edit while a sale is queued can make local and cloud stock differ. | **release blocker** | R06: send immutable sale-time usage or versioned recipes with the transaction; test a recipe change during offline sales. |
| R05-12 | The agreed v1 refund requires a Cashier POS reversal with original-day accounting and current-till cash tracking. [Android checkout/history](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/CheckoutRepository.kt) has no reversal queue/action. The [server stock reversal helper](../supabase/migrations/202609080001_secure_sale_reversals.sql) and IMS admin action do not fulfill that flow. | **release blocker** | R06: implement and test POS full reversal, reason, restock/waste, offline replay, original-day report correction, current-till cash, and Owner audit view. |
| R05-13 | [SyncRepository](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/SyncRepository.kt) marks sales synced only after `accepted`/`duplicate`; [SQL sale fixture](../supabase/tests/sale_reversals.sql) and [unit tests](../apps/android-pos/app/src/test/java/com/icecreampost/pos/data/repository/SyncRepositoryTest.kt) exercise replay. The passing isolated fixture covers stock-receipt retry. The backend duplicate check currently accepts a matching receipt number and total even if transaction ID or items differ; [receipt generation](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/CheckoutRepository.kt) is timestamp-derived. | **release blocker** | R06: require ID/payload identity on duplicates, make receipt numbers collision-resistant, and test an ID/receipt conflict with equal totals. |
| R05-14 | Immediate and periodic WorkManager jobs are individually unique, but [manual sync](../apps/android-pos/app/src/main/java/com/icecreampost/pos/ui/PosViewModel.kt) also calls the same repository; [SyncRepository](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/SyncRepository.kt) has no shared mutex. Simultaneous runs can read the same queue and race on marks/pulls. No real Room overlap test exists. | **release blocker** | R06/R09: serialize all sync entry points and test manual/background overlap, timeout after server commit, and process death. |
| R05-15 | Separate [deductions](../supabase/migrations/202610010002_pos_activation_and_deduction_alignment.sql) and [Android close](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/BusinessDayRepository.kt) distinguish cash removal from additional profit expense. The [PGlite fixture](../supabase/tests/separate_revenue_deductions.sql) and [Android unit tests](../apps/android-pos/app/src/test/java/com/icecreampost/pos/data/repository/BusinessDayRepositoryTest.kt) cover the distinction. A missing closing/deduction RPC leaves a queued error rather than a synced acknowledgement. | **tested** | R08/R09: verify PHP 200/15/10 example in live development and Owner IMS; reject incompatible server versions before POS rollout. |
| R05-16 | [SyncRepository](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/SyncRepository.kt) uploads the **closed** day before queued sales, then can upload a closing even after a permanent sale failure; it only waits for day and deduction records. [Server closing](../supabase/migrations/202609290003_separate_revenue_deductions.sql) calculates from server-known sales and can accept/mark the closing synced with missing sales. A later sale retry does not automatically reopen the already-synced closing. | **release blocker** | R06: block final close/closing acknowledgement while sales or stock changes for that day are pending/rejected; reconcile client and server counts/totals and retest late retries. |
| R05-17 | [Device recovery SQL](../supabase/migrations/202610010002_pos_activation_and_deduction_alignment.sql) and [activation fixture](../supabase/tests/activation_alignment.sql) cover active-device transfer and server-known recovery totals. [POS replacement](../apps/android-pos/app/src/main/java/com/icecreampost/pos/ui/PosViewModel.kt) checks that the day is closed and pending work is zero. The PGlite fixture passed. | **tested** | R09: test the actual Room migration/update and replacement on a physical phone with a nonempty queue. |
| R05-18 | A lost phone's unsynced queue cannot be reconstructed from Supabase. [Scope](FIRST_RELEASE_SCOPE.md) accepts numbered-paper continuation and manual reconciliation; the [POS recovery guide](../supabase/README.md) warns that the recovered totals include only server-known records. | **accepted limitation** | R18 must supply the numbered sheet, named support contact, stop conditions and manual reconciliation path before pilot; do not claim phone-only sales were recovered. |
| R05-19 | [Android](../apps/android-pos/app/src/main/java/com/icecreampost/pos/di/NetworkModule.kt) uses HTTP BASIC logging (request line/status, no headers or body) and app sync logs IDs/status, not passwords or tokens. [Web session](../apps/web-ims/src/lib/session.ts) uses session storage. This rechecks the old logging concern, but is not a penetration test. | **fixed** | R08/R09: inspect release logs and verify tokens/passwords do not appear; do not enable BODY logging. |

## Verification and interpretation

- `node supabase/tests/pos_stock_receiving.integration.mjs --extended --activation`
  passed on 6 October: migrations loaded in order, stock receipts, login limit,
  classification, stock setting, recipes/closings, activation, deductions,
  administrator data reset definition and administrator day close.
- The recipe fixture originally failed because it called the mutating posting
  function and stock reads inside one Boolean expression. SQL did not guarantee
  that evaluation order. The fixture now posts in a separate statement, then
  checks two ledger rows and stock `920 g`/`19 pieces`; the full extended run
  passed. This was a **test defect**, not evidence of wrong recipe arithmetic.
- The separate `sale_reversals.sql` and `rbac_business_days.sql` fixtures are
  present but are not part of the passing PGlite runner. A trial addition of
  `sale_reversals.sql` returned `P0002 Transaction not found`; its cause has not
  been isolated, so it is **not counted as a pass or a confirmed application
  defect**. Run both in disposable PostgreSQL in R08 and investigate there.
- [PR run 37284888741](https://github.com/LemorDev/Ice-Cream-System/actions/runs/37284888741)
  passed web, Android and the previous narrower database job at `3cf4158`.
  The broadened recipe fixture CI change in this audit still needs its PR run.
- No development Supabase migration push, live backend test, physical phone,
  signed release, or production write was performed for R05.

The release guide's 20 September setup snapshot is historical: the feature
work was committed in R03, `develop` and draft PR #1 were established in R04,
and `.github/workflows/pr-checks.yml` now exists. Web, Android and Database
checks are required on `develop` and `main`; the 5 October PR run cited above
passed all three. No hosting was connected at the last verified R04 check.
The old test counts and old claims that recipes, fractional stock, itemized
deductions and Manila calendar conversion were absent must not be used as
current release evidence.

## R06 order

Repair data correctness first: R05-12 refund, R05-08 operating-day ownership,
R05-16 closing/queue gating, R05-09 historical costs, and R05-11 recipe
snapshots. Then R05-05/06 pagination, R05-13/14 replay and serialization,
R05-02 local stall isolation, and R05-04 account recovery. Re-run affected
fixtures and move to R08/R09 only when no blocker above remains.
