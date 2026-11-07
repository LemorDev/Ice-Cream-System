# R09 software readiness — 13 October 2026

Software checks passed on the current working tree. Actual phone acceptance is
pending; no phone was connected when `adb devices -l` was checked. Use the
[phone checklist](R09_PHONE_TEST_CHECKLIST.md) to record the manual R09 results.

## Login defect and repair

The reported POS error was reproduced by a new test using the real Retrofit
converter and HTTP response bodies. Cashier login succeeded, then
`get_recovered_pos_day` returned the valid JSON body `null` for an ordinary
registered phone with no recovery incident. Retrofit's serializer lookup uses
the Java return type and does not retain the Kotlin nullable return marker. It
therefore attempted to deserialize `null` as a recovered-day object.

The client now uses an explicit response wrapper whose serializer accepts
either a recovered-day object or JSON `null`. Valid null responses complete
sign-in and preserve the active registration. Malformed responses still fail;
a failed lookup restores the previous token and does not save the new session
or stall binding. No backend migration was needed for this login repair.

`SessionHttpContractTest` covers registered login with null recovery, a recovery
object with its totals, an unactivated login that skips recovery, incorrect
credentials, and malformed recovery responses. The previous mock-only test
could not expose this converter failure.

## Ordered software verification

| Step | Check | Result |
| --- | --- | --- |
| 1 | Focused login HTTP regression before the fix | Reproduced the screenshot's JSON null decoding failure; 1 of 4 tests failed as expected. |
| 2 | Focused login HTTP and session tests after the fix | All 14 tests passed. |
| 3 | All migration SQL and extended database fixtures in disposable PGlite | Passed after repairing the stale email-based login fixtures and adding powder coverage; 49 migrations and 14 fixture groups. No fixture ran against a live project. |
| 4 | IMS lint | Passed. |
| 5 | IMS unit tests | 50 passed; no failures or skips. |
| 6 | IMS TypeScript and development bundle | `build:development` passed with development environment isolation. Vite retains its existing bundle-size advisory. |
| 7 | Full Android development unit tests | 105 passed; no failures, errors, or skips. |
| 8 | Android development lint | Passed with 9 warnings: launcher icon shape, dependency catalog style, and the intentional device identifier. No lint errors. |
| 9 | Room upgrade checks in in-memory SQLite | Versions 11, 12, and 13 each upgrade to 14, match exported schema columns/indexes, and preserve pending sales, items, stock movements, operating day, and session fields. This checks actual migration SQL; Android Room runtime and handset updates remain manual R09 checks. |
| 10 | Development APK build and signature verification | `assembleDevDebug` and `apksigner verify` passed. Actual APK package is `.dev`; minimum SDK 26 and target SDK 36. |
| 11 | Dependency audit | `pnpm audit --audit-level=moderate`: no known vulnerabilities. |
| 12 | Development migration history, preview, apply, and verification | Reviewed and applied only `202610130002_fix_powder_rpc_contract.sql`; 49 local/remote versions match, and the subsequent dry run is empty. |
| 13 | Read-only development HTTP contract | 11 protected RPCs deny anonymous access; POS employee-ID and management identifier endpoints return empty arrays for random nonexistent accounts. No synthetic rows or real credentials were used. |

Android Gradle retains its existing compile-SDK compatibility advisory; it did
not prevent tests, lint, or APK assembly. These are local candidate checks, not
a passing PR run or a completed phone acceptance task.

## Additional database repair found during testing

The new powder fixture exposed an ambiguous `product_id` reference while
editing powder tracking. The already-applied migration was preserved. Forward
migration `202610130002_fix_powder_rpc_contract.sql` qualifies identity and
locks the product while changing tracking, preventing concurrent receipts from
leaving sealed packs attached to disabled tracking. Pack opening now uses a
separate permission guard and locks the operating day for retry/closing order.

The fixture covers initial sealed counts, product edits, receipts, opening
replay and conflicts, over-opening rejection, negative hopper sales, total
stock exhaustion, POS/IMS response fields, anonymous denial, and disabling
tracking after reconciliation.

The linked project was verified as **`fhyqrgxwdqlyzxsnpthr`**. Production was
not contacted. The full rollback fixtures ran only in disposable local PGlite.

## Exact development candidate

| Item | Value |
| --- | --- |
| Source | Working tree on `codex/inventory-recipes-ui`, based on `6e07f1bf4e20774835079f5d12ef5b1d23736939`; current changes are not yet a frozen release commit. |
| APK | `apps/android-pos/app/build/r09-candidate/coolerz-pos-dev-r09-20261013.apk` |
| Manifest | `apps/android-pos/app/build/r09-candidate/manifest.json` |
| Package / label | `com.icecreampost.pos.dev` / `Coolerz POS (DEV)` |
| Version | `0.1.0-dev`, version code `1`, Room schema `14` |
| Environment | `development`, `https://fhyqrgxwdqlyzxsnpthr.supabase.co` |
| APK SHA-256 | `1A10D6DAD3637FB9AA73C87C85CD006DB473E03AF2E8D40F1A491F7DD668F354` |
| Signing certificate SHA-256 | `7e7a6102a80ae197eafe3cd38eabb8ccd4d1dfadacba003ac42151c68e450830` |

The APK was built with the configured existing `.local/debug.keystore`.
The installed phone certificate, queue, and actual login result remain
unverified until the handset is connected or manually checked. R09 is in
progress, not complete. Web hosting and production distribution remain later
release tasks.

## Reproduce from repository-root PowerShell

```powershell
Set-Location 'C:\Desktop Apps and Files\Ice cream system'
node supabase/tests/pos_stock_receiving.integration.mjs --extended --activation
pnpm --dir apps/web-ims lint
pnpm --dir apps/web-ims test
pnpm --dir apps/web-ims build:development
node apps/android-pos/scripts/verify-room-upgrade.mjs
pnpm audit --audit-level=moderate
```

Android checks:

```powershell
Set-Location 'C:\Desktop Apps and Files\Ice cream system\apps\android-pos'
.\gradlew.bat :app:testDevDebugUnitTest --offline
.\gradlew.bat :app:lintDevDebug --offline
.\gradlew.bat :app:assembleDevDebug --offline
```

Migration workflow for the new forward repair, already applied and verified:

```powershell
Set-Location 'C:\Desktop Apps and Files\Ice cream system'
Get-Content .\supabase\.temp\project-ref
& .\node_modules\.bin\supabase.cmd migration list --linked
& .\node_modules\.bin\supabase.cmd db push --dry-run
& .\node_modules\.bin\supabase.cmd db push
& .\node_modules\.bin\supabase.cmd migration list --linked
```
