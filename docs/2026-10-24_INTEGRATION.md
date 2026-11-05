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

- Web: lint, 53 unit tests, and development build passed locally. Vite was
  updated from 6.4.3 to 6.4.5 after CI found new advisories; the repeated
  dependency audit found no known vulnerabilities.
- Isolated PGlite: all migrations and 15 regression fixture groups passed.
  This emulates the Storage schema; the PR workflow additionally passed the
  migrations and photo/powder fixtures in disposable local Supabase
  ([run 40080282425](https://github.com/LemorDev/Ice-Cream-System/actions/runs/40080282425)).
- SQLite upgrade checks: versions 3, 4, 11, 12, 13, 14 and PR #2's alternate
  version 4 upgrade to exported schema 15 with sample pending data intact.
- Android: all 119 development unit tests passed with zero failures/skips;
  lint passed with zero errors and 57 warnings; the development APK assembled.
- Actual phone testing was explicitly deferred by the user on 24 October.
  R09 closing acknowledgment, outstanding session/retry/isolation cases, and
  the new biometric/photo UI acceptance remain pending.
- The user deferred deployment to 25 October, including pending schema changes.
  Cloudflare is not authenticated and no site has been published. Owner/Cashier
  sign-off has not occurred. No production deployment is part of this work.
  Pages supplies SPA fallback and standard headers by default; no catch-all
  rewrite should override static assets. See [Cloudflare's serving behavior](https://developers.cloudflare.com/pages/configuration/serving-pages/).

## Development migrations

The linked reference was checked as `fhyqrgxwdqlyzxsnpthr`. Remote history on
24 October matched through `202610130004`. The reviewed dry run listed only:

1. `202610140001_product_images.sql`
2. `202610240001_integrate_product_images.sql`

At this point these are pending; a dry run is not an application.
Application is explicitly deferred to the next deployment session.
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

## Built development APK

`apps/android-pos/app/build/outputs/apk/dev/debug/app-dev-debug.apk`

SHA-256: `122CC8E773C36399D6FEE7A37C9B139754AE34EBFF5ABDA12CEF1508B1C7D06F`.
It uses the existing development signing key. It has not been installed on the
phone during this integration session. Recheck the checksum before using it.

## Next deployment session — 25 October

1. Check the current PR #1 head and its required checks; use one identified
   candidate throughout the deployment. Keep it in development.
2. Recheck the development project and the exact two-migration dry run above.
   Apply only after reviewing that list, then verify remote history and an
   empty dry run. No live test-fixture SQL should be used.
3. Complete Cloudflare login and review the existing Pages projects before
   creating or changing one. Follow `FIRST_RELEASE_DEPLOYMENT_PLAN.md`: root
   build command `pnpm --dir apps/web-ims build:development`, output
   `apps/web-ims/dist`, development environment only. Promote reviewed source
   to `develop` before enabling its branch deployment.
4. Verify HTTPS, DEVELOPMENT DATABASE label, development Supabase identity,
   asset loading and Admin/Owner login. Record the URL and candidate commit.
5. Resume the deferred phone recovery check before new sales, then complete
   the combined UAT draft. Handset acceptance and Owner/Cashier sign-off are
   still required before production.
