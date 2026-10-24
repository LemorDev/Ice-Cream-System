# Combined development candidate — 24 October 2026

Local work was preserved in `f2234f5` before merging `origin/main` at
`3a3e4f3` (PR #2). The candidate retains powder inventory, named purchase
units, employee-ID login, recovered-day reconciliation, refunds, and paged
catalogs while adding photos, biometric unlock, grid selling, and open/close
confirmation.

## Compatibility repairs

- Room version 15 adds photos to the local version-14 database. A direct 4→15
  bridge distinguishes PR #2's photo schema from the earlier recipe schema;
  both preserve queued transactions, stock movements, days, sessions, and photos.
- `202610240001_integrate_product_images.sql` preserves powder/recipe product
  saving and adds photos to paged POS catalogs and IMS financial snapshots.
  An omitted photo field preserves the image for older clients; explicit null
  removes it. The earlier `202610140001` migration remains unchanged.
- The grid uses recipe availability and respects busy/closed-day state.
  Employee-ID login, existing operating-day review, privacy settings, and
  device replacement remain available alongside biometric unlock.

## Verification

- Web: lint, 53 unit tests, and development build passed locally.
- Isolated PGlite: all migrations and 15 regression fixture groups passed.
  This emulates the Storage schema; the PR workflow additionally runs the
  migrations and photo/powder fixtures in disposable local Supabase.
- SQLite upgrade checks: versions 3, 4, 11, 12, 13, 14 and PR #2's alternate
  version 4 upgrade to exported schema 15 with sample pending data intact.
- Android development APK assembled. Full unit tests and lint are being
  finalized; record the final result before treating the candidate as verified.
- Actual phone testing was explicitly deferred by the user on 24 October.
  R09 closing acknowledgment, outstanding session/retry/isolation cases, and
  the new biometric/photo UI acceptance remain pending.
- Development hosting is prepared with SPA routing and response headers;
  publication requires Cloudflare authentication. Owner/Cashier sign-off has
  not occurred. No production deployment is part of this work.

## Development migrations

The linked reference was checked as `fhyqrgxwdqlyzxsnpthr`. Remote history on
24 October matched through `202610130004`. The reviewed dry run listed only:

1. `202610140001_product_images.sql`
2. `202610240001_integrate_product_images.sql`

At this point these are pending; a dry run is not an application.
From repository-root PowerShell, inspect, preview, apply, and verify:

```powershell
Set-Location 'C:\Desktop Apps and Files\Ice cream system'
Get-Content .\supabase\.temp\project-ref
& .\node_modules\.bin\supabase.cmd migration list --linked
& .\node_modules\.bin\supabase.cmd db push --dry-run
& .\node_modules\.bin\supabase.cmd db push
& .\node_modules\.bin\supabase.cmd migration list --linked
```

Review the two files and target before applying. Stop if the pending list
differs. Never use `db reset --linked` or run SQL test fixtures on a live project.

## Reproduce software checks

```powershell
pnpm --dir apps/web-ims lint
pnpm --dir apps/web-ims test
pnpm --dir apps/web-ims build:development
node supabase/tests/pos_stock_receiving.integration.mjs --extended --activation
node apps/android-pos/scripts/verify-room-upgrade.mjs
Push-Location apps/android-pos
.\gradlew.bat :app:testDevDebugUnitTest :app:lintDevDebug :app:assembleDevDebug
Pop-Location
```

The main local migration evidence and previous phone results are retained in
`R09_PHONE_TEST_CHECKLIST.md`, `R09_CLOSING_RECONCILIATION.md`, and
`R09_SOFTWARE_TEST_EVIDENCE.md`. The combined UAT draft is
`DEVELOPMENT_UAT.md`.
