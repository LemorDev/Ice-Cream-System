# First release: development, deployment, and acceptance plan

Status: proposed plan, 3 October 2026. No hosting, production database migration, or POS distribution is authorized or performed by this document.

For incremental execution and recorded completion evidence, use the [first-release task tracker](FIRST_RELEASE_TASKS.md).

## The simple answer

`pnpm dev` at the repository root is only an alias for `pnpm --dir apps/web-ims dev`. Both start a local Vite development server; neither deploys the app or determines which database is used. The current Vite configuration requires development mode to point at the development Supabase project. Production is made with `pnpm --dir apps/web-ims build`, which produces static files in `apps/web-ims/dist` for hosting. `pnpm --dir apps/web-ims build:development` produces a test build connected to the development project.

Use **Cloudflare Pages** for the web files and **Supabase** for the database and API. Create two separate Pages projects from this repository so their build settings cannot cross over:

| Environment | Web release | Supabase | Users and data |
| --- | --- | --- | --- |
| Local development | `pnpm --dir apps/web-ims dev` on the developer's computer | Existing development project | Test accounts and test sales only |
| Online acceptance | Pages project built from `develop`; `pnpm --dir apps/web-ims build:development` | Existing development project | Owner/cashier UAT with test data; usable on phones |
| Live operation | Pages project built from protected `main`; `pnpm --dir apps/web-ims build` | Existing production project | Real accounts, stock, and sales |

In both Pages projects, use the repository root as the build root and `apps/web-ims/dist` as the output directory. Set `VITE_APP_ENV`, `VITE_SUPABASE_URL`, and `VITE_SUPABASE_ANON_KEY` for each project; the development project also needs `VITE_SUPABASE_PRODUCTION_URL` for the isolation check. Verify the final environment label and project URL after each deployment. The Vite variables and anon/publishable key are visible in the browser, so never put a database password or secret/service-role key there. Configure the production Pages project to deploy only `main` and the development project only `develop`; turn off unrelated preview branch deployments. A reviewed merge into `main` is the production release action, so protect that branch first. Cloudflare Pages settings and the exact domain still need to be created and checked; no Pages integration is present in the repository today.

The eventual production address can be `https://ims.<your-domain>` and the development address `https://dev-ims.<your-domain>`. These are examples, not existing domains. The default `*.pages.dev` HTTPS address works for the initial acceptance run if a domain has not been purchased. Cloudflare Pages can attach a custom domain later.

### How people access it

- **System administrator / IMS:** Open the live HTTPS web address on a computer or mobile browser, sign in with the System admin account, and manage products, inventory, staff, devices, and reports. Test the specific admin tasks needed on the intended phone before promising mobile-only administration.
- **Owner:** Open that **same** live address on a phone or computer and sign in with an Owner account assigned to the stall. The app presents the read-only Owner dashboard automatically. On iPhone, open the address in Safari and use **Share → Add to Home Screen**. This is a website shortcut/app-like launch; it requires internet for live figures. It is not a second website or a second web deployment.
- **Cashier / POS:** Install the signed **production Android APK** on the stall's Android phone, sign in with the production Cashier account and stall code, then activate the device with a one-time code from the System administrator. The POS can save sales locally while offline and sync when connected. The development APK has a separate `.dev` package and connects only to the development project.

Web hosting does not host the POS app. For the first one-stall internal release, distribute a controlled, signed APK directly to the selected device, retaining the exact file and checksum. Consider a Google Play internal track later if remote installation and updates become important. Keep the production package ID and signing certificate stable: an update must install over the existing app to preserve its local Room queue. Do not uninstall a phone with unsynced sales.

## New day-to-day software process

1. Start a short-lived feature branch from `develop` (normally `codex/<feature>`). Work locally against the **development** Supabase project and use only test records.
2. Add schema changes as ordered, reviewed files in `supabase/migrations`. Check the linked project reference and `supabase db push --dry-run` before pushing to **development**. Never use `db reset --linked` on either live project. Put compatibility changes on the server before a client that needs them.
3. Run focused web, Android, and SQL checks; test the actual phone for sales, offline queue, restart, sync, closing, and in-place update. Open a PR into `develop`. After review and checks, merge and let the development Pages site rebuild; install the matching development APK.
4. Owner and cashier perform acceptance tests on the development URL and `.dev` POS. Log each issue with steps, expected result, actual result, and whether it blocks the release. Fix issues on feature branches and repeat the affected tests.
5. Freeze a release candidate. Review the exact code revision, migration list, web build configuration, and Android version. Promote that revision with a PR from `develop` to `main` after the gates below pass. Keep a release tag and the signed APK tied to that commit.
6. For later fixes, branch from `develop`; for urgent production fixes, branch from `main`, release the fix, then merge it back to `develop`.

The existing [branching guide](RELEASE_AND_BRANCHING_PLAN.md) expands this workflow. It is a proposal: the current checkout is still on `codex/inventory-recipes-ui`, has many uncommitted/untracked changes, `develop` does not yet exist locally, and no CI workflow was found. Review and preserve that work before branch setup or release.

## Release sequence and gates

### A. Prepare the candidate

- Agree on the first-release scope: one stall, one active POS phone, products/recipes and inventory units, prices, opening stock, operating-day cutoff, cash deductions, and refund/void policy. Defer features only when the cashier has a documented way to handle the case and the owner accepts the reporting effect.
- Re-audit the [project review](PROJECT_REVIEW.md) and [release guide](RELEASE_AND_BRANCHING_PLAN.md) against current code. Several review findings are dated; mark each verified, fixed, or still blocking. Specifically verify login rate limiting/account recovery, cross-stall access, report pagination and date/cost accuracy, sync idempotency, queue recovery, and consistency of deduction/closing totals.
- Add required PR checks for web lint/tests/build, Android unit tests/build, and isolated database checks. Pin the CI Node/pnpm and Android toolchain versions so the release can be rebuilt from the same commit.
- Run all migrations and rollback-only SQL fixtures in a disposable database, then test the candidate against the development Supabase project. Migrations named `reset` define controlled functions or reporting behavior; still inspect their bodies before applying. Never run destructive test fixtures against production.
- Configure the two Pages projects and the development online site. Ensure the development site displays **DEVELOPMENT DATABASE**, and verify the production build displays the production label and uses a different project URL.
- Configure secure production release signing, store and back up the keystore outside Git, and set an incrementing Android `versionCode`. Rehearse an **in-place** update with a nonempty development queue on a physical phone.

**Gate A:** No known issue that can lose, duplicate, expose, or materially misstate sales, stock, cash, or owner reports; reproducible web builds and signed Android release build; tested recovery path.

### B. User acceptance on development

Use the online development IMS and development POS together. Record pass/fail and actual amounts for these scenarios:

| Tester | Required walkthrough |
| --- | --- |
| System administrator | Create/assign owner and cashier, set products/recipes/prices/opening stock, activate POS, see records arrive, and verify an unassigned user cannot access the stall |
| Cashier | Open day, sell with cash/change, receive or adjust stock if in scope, record a deduction, close, restart the app, work offline then reconnect, and install an update without losing queued work |
| Owner | Sign in on the intended mobile phone, check sales and profit against receipts and manual calculations, review closing/deductions, and add the dashboard to the home screen if desired |
| Developer | Verify each sale uploads once, queue reaches zero, rejected items are visible, dates agree across POS/IMS, and backup restore works in an isolated project |

**Gate B:** Cashier and Owner sign off on the agreed scope and figures. All blocking defects are fixed and retested. Test data stays in development and is never copied into the live ledger.

### C. Production preparation and deployment

1. Inventory the **existing** production Supabase data, applied migrations, active accounts, installed POS package/signing identity, and any pending phone queue. Do not assume production is empty or re-run first-admin bootstrap blindly.
2. Choose the backup plan for real sales, take a pre-migration backup, and demonstrate restore into an isolated project. Supabase's automatic daily backups depend on plan; if the project has no automatic backups, arrange a tested off-site export before going live. A cloud backup cannot recover sales still only on a phone.
3. During a quiet period, sync the current POS queue to zero and reconcile existing sales. Review the pending production migrations with `migration list` and `db push --dry-run` while explicitly linked to the production project. Apply only the reviewed migrations, with a recorded change window and a compatible client rollout order.
4. Merge the accepted candidate to protected `main`. Deploy the production Pages build and verify HTTPS, environment label, login, role limits, and key reports from both desktop and mobile. Set up the real Owner and Cashier accounts, stall, catalog, and opening stock.
5. Install the exact signed production APK on the selected Android phone, activate it, open the day, and verify catalog, stock, a controlled first sale, sync acknowledgment, and the same sale in IMS. Keep the previous signed artifact and release record. Avoid an Android downgrade after a Room schema change; prepare a forward fix if needed.

**Gate C:** Successful end-to-end production smoke test, reconciled starting state, named support contact, cashier paper fallback sheet, and recorded backup/build versions.

### D. Supervised real-sales pilot

Run one stall for at least three consecutive fully reconciled operating days. These are real sales in **production**; do not reset them at the end of UAT. At each close, compare receipt count, gross sales, deductions, expected cash, counted cash, inventory movements, and Owner dashboard figures. The administrator resolves any discrepancy before the next shift. Stop POS use and use the documented paper process for missing/duplicate sales, unexplained cash or stock, unauthorized access, crash loops, or an unrecoverable queue. Preserve the phone and its data for reconciliation.

After the Owner and Cashier accept the pilot, mark the release operational. Keep daily closing reconciliation and release records for each later update.

## Decisions needed before execution

1. Confirm the production domain or use the temporary `pages.dev` address for the pilot.
2. Confirm the first-release feature scope, especially recipes, refunds/voids, business-day cutoff, and how deductions affect cash and profit.
3. Choose the real-sales backup/recovery target and who handles a stalled POS or discrepancy during the pilot.

## Provider references

- [Cloudflare Pages build configuration](https://developers.cloudflare.com/pages/configuration/build-configuration/) and [branch deployment controls](https://developers.cloudflare.com/pages/configuration/branch-build-controls/)
- [Cloudflare Pages custom domains](https://developers.cloudflare.com/pages/configuration/custom-domains/)
- [Supabase database backups](https://supabase.com/docs/guides/platform/backups)
- [Android app signing and update identity](https://developer.android.com/studio/publish/app-signing)
