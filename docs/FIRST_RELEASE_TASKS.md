# First-release task tracker

Updated: 5 October 2026. Source plan: [First-release deployment plan](FIRST_RELEASE_DEPLOYMENT_PLAN.md).

This is the working checklist for the first IMS, Owner dashboard, and Android POS release. Complete one numbered task at a time, record its evidence in this file, and start the next ready task. `Done` means the stated completion check passed; a command merely running is not enough. Keep development test data out of production. Real sales begin only in the supervised production pilot.

## Status and known starting point

Status key: `[ ]` To do · `[-]` In progress · `[x]` Done · `[!]` Blocked. For a blocked task, record the exact missing decision or access and continue with an independent ready task.

The 4 October local check found the branch `codex/inventory-recipes-ui` with many modified and untracked files, including both web and Android work and pending SQL migrations. Local `main` exists; local `develop` and a `.github` workflow directory were not found. The prior plan found production release signing unconfigured. These observations are a starting snapshot, not a production readiness assessment. Preserve the current work before switching branches or releasing.

**Next task: R05 — re-audit release risks against current code.** R04 established the protected branch and PR path; R07 can expand the first passing PR checks with isolated database testing.

## 1. Establish the release baseline

- [x] **R01 — Inventory current source and deployments** (Developer). Record the current commit/branch, changed and untracked files grouped into web, Android, database, docs, and generated files; check which files may contain credentials before staging anything. Read-only inventory of the existing production Supabase migration history, current web URL/revision if any, installed POS package/version/signature, and pending POS queue when access is available. **Done when:** `Release baseline` below has verified values or explicitly says `unknown` for each production item; no current work was lost.
- [x] **R02 — Agree on first-release scope** (Owner + Developer + Cashier; needs R01). Write the supported one-stall workflows and acceptance rules for recipes/units, refund or void handling, operating-day cutoff, cash deductions versus profit deductions, inventory corrections, and what the cashier does when the POS is unavailable. **Done when:** each item is marked `in v1` or `deferred with procedure`, and the Owner and Cashier accept the scope.
- [x] **R03 — Preserve and organize the current work** (Developer; needs R01). Review every changed/untracked file, keep secrets and generated output out of Git, group intended work into understandable commits on the current branch, and record the resulting commit IDs. **Done when:** the feature work is committed safely, unrelated changes are preserved, and the source used for testing is identifiable.
- [x] **R04 — Establish the branch and review flow** (Developer; needs R03). Fetch the remote state; create or verify `develop` from the reviewed `main` baseline; open the feature PR into `develop`; add practical protections for `develop` and `main` and prevent unintended production auto-deploys while configuring them. **Done when:** branch ancestry, PR base, required checks, and production deployment trigger are documented and verified.

**Gate 1:** A known candidate source and agreed v1 behavior. The large local change set is preserved before branch or release operations.

### R02 scope decision sheet

The detailed rules, acceptance checks, implementation gaps, and sign-off record are in the [first-release scope](FIRST_RELEASE_SCOPE.md). The user reported that the Owner and Cashier accepted scope revision `0bba1be` on 5 October 2026. A tested POS refund/void flow and a day that can cross midnight remain release blockers until implemented and verified.

| Decision | v1 choice / acceptance rule |
| --- | --- |
| Products, recipes, ingredient units, and opening stock | **In v1**; System administrator prepares catalog, Owner approves actual data in R17/R21 |
| Refunds, voids, and mistaken sales | **In v1**; tested POS flow required; Cashier may correct a closed day with audit trail and no prior approval |
| Operating day that crosses midnight | **In v1**; one day spans midnight until deliberate close |
| Cash deductions versus profit deductions | **In v1**; till cash and new expense handled separately |
| Stock receiving, waste, and corrections | **In v1**; Cashier receives, administrator corrects |
| Cashier procedure during internet/POS failure | Offline POS sales **in v1**; unavailable POS **deferred with numbered-paper procedure**; pilot support/stop details belong to R18 |

## 2. Make the development candidate trustworthy

- [ ] **R05 — Re-audit release risks against current code** (Developer; needs R02–R03). Recheck the dated [project review](PROJECT_REVIEW.md) and [release guide](RELEASE_AND_BRANCHING_PLAN.md). Record evidence for role/stall isolation, login rate limiting and account recovery, pagination, report dates and historical costs, recipe/stock arithmetic, sync replay, close/deduction totals, queue and device recovery. **Done when:** each finding is classified `fixed`, `tested`, `release blocker`, or `accepted limitation` with evidence; no dated finding is treated as current without rechecking it.
- [ ] **R06 — Resolve release blockers** (Developer; needs R05). Make a separate issue or checklist item for each blocker from R05; fix the server and clients in compatible order. **Done when:** no unresolved issue can lose, duplicate, expose, or materially misstate a sale, cash, stock, or Owner report. Record any accepted limitation in the cashier/Owner instructions.
- [ ] **R07 — Add repeatable CI checks** (Developer; needs R04). On PRs run web lint, tests, and build; Android unit tests and build; and isolated database migration/fixture checks. Pin the required Node/pnpm, Java/Android, and CLI versions. **Done when:** all checks run on a PR, failures block merging, and artifacts/logs identify the tested commit.
- [ ] **R08 — Verify the development database contract** (Developer; needs R05–R06). Confirm the CLI links to the development project, inspect `migration list` and `db push --dry-run`, apply only reviewed pending migrations there, and execute rollback-only SQL fixtures on a disposable database. Test development Supabase with two stalls, role restrictions, duplicate uploads, and matching web/POS RPC behavior. **Done when:** migration history and test evidence match the candidate; no test fixture ran on production.
- [ ] **R09 — Test POS on the actual phone** (Developer + Cashier; needs R08). Use the `.dev` app and development Supabase. Cover open/sell/close, deductions, stock, offline queue, restart, reconnect, failed/retried upload, session expiry, and an in-place update with pending data. **Done when:** each accepted transaction appears once in IMS, the phone's queue reaches zero after acknowledgment, and the update preserves local records.

**Gate 2:** Automated and real-device evidence supports the release candidate. Any blocker discovered in R08–R09 returns to R06.

## 3. Put acceptance testing online

- [ ] **R10 — Deploy online development IMS** (Developer; needs R04 and R08). Create the development Cloudflare Pages project from `develop`, with repository-root build `pnpm --dir apps/web-ims build:development`, output `apps/web-ims/dist`, and development-only Vite values. Limit branch deployments to the intended branch. A `pages.dev` HTTPS address is enough initially. **Done when:** the phone can open the URL, it displays **DEVELOPMENT DATABASE**, it reaches the development Supabase project, and Owner/System admin access works as designed.
- [ ] **R11 — Write the UAT script and expected figures** (Developer + Owner + Cashier; needs R02, R08–R10). Specify test accounts, products, starting stock/cash, transaction sequence, expected receipt count, revenue, COGS, deductions, profit, and closing figures. Include mobile layout and Owner home-screen access. **Done when:** each scenario has an expected value and a place to record actual value, result, tester, and defect ID.
- [ ] **R12 — Run and repair user acceptance testing** (Owner + Cashier + Developer; needs R09–R11). Owner and Cashier perform the script using the online development IMS and `.dev` POS. Record defects with steps and evidence; repair on feature branches and rerun affected scenarios. **Done when:** all blocking cases pass and Owner/Cashier sign off on the agreed v1 scope. Development test records stay in development.
- [ ] **R13 — Freeze and identify the candidate** (Developer; needs R12). Record the reviewed commit, migration list, passing checks, development web deployment, Android APK version, and UAT evidence. **Done when:** one exact candidate can be rebuilt and promoted; subsequent changes require another candidate check.

**Gate 3:** Written Owner/Cashier UAT sign-off for a specific candidate, with no blocking defects.

## 4. Prepare production safely

- [ ] **R14 — Set up signed Android releases** (Developer; can begin after R03; final build needs R13). Configure production release signing and secure keystore backup outside Git; choose version name/code, record package ID and certificate fingerprint, and build a signed APK from the candidate. **Done when:** the APK installs on a test device, the keystore can be retrieved for future updates, and a same-signature update succeeds without deleting Room data.
- [ ] **R15 — Audit production state and rollback options** (Developer + System administrator; needs R13). Verify the existing production project's data, applied migrations, roles, active POS binding, current web build, installed phone signing identity, and pending queue. Write the server/client rollout order and a forward-fix plan if a Room upgrade cannot be rolled back. **Done when:** all production items are known and the current phone is reconciled or its pending data is explicitly accounted for.
- [ ] **R16 — Prove database backup and recovery** (Developer + Owner; needs R15). Choose backup frequency and tolerated data loss/downtime. Take or configure a production backup and restore a copy into an isolated project; document who performs recovery. **Done when:** a restore has been demonstrated and the Owner accepts the recovery target. Remember that unsynced phone-only records are absent from cloud backups.
- [ ] **R17 — Prepare production web hosting and setup data** (Developer + System administrator; needs R13 and R15). Configure the production Pages project for `main`, the production build command, production Supabase URL/publishable key, and optional custom domain. Keep automatic production deployment disabled until R20. Prepare a reviewed list of real Owner/Cashier accounts, catalog, prices, and opening stock for entry after the matching server and web release. **Done when:** build settings are independently checked and the setup data is ready; no unapproved production write or test sale has occurred.
- [ ] **R18 — Prepare the pilot runbook** (Owner + Cashier + Developer; needs R02 and R15–R16). Name the on-call contact, paper receipt and reconciliation procedure, stop conditions, first-shift window, and daily sign-off sheet. **Done when:** the Cashier can continue recording sales safely during a POS/backend outage and knows whom to contact.

**Gate 4:** Signed build, verified backup, reconciled starting state, safe web configuration, and a staffed pilot window.

## 5. Release and operate the first version

- [ ] **R19 — Apply reviewed production migrations** (Developer + System administrator; needs Gates 3–4). During the agreed quiet period, sync the existing phone, take the pre-change backup, verify the production project reference and `db push --dry-run`, then apply only the approved migrations. Run safe contract checks without executing rollback-only fixtures on production. **Done when:** the migration list matches the candidate and existing clients remain compatible; stop if the preview differs from the approved list.
- [ ] **R20 — Promote and deploy the web candidate** (Developer; needs R19). Merge the accepted candidate from `develop` to protected `main`, enable/deploy the production Pages build, and verify HTTPS, environment label, Supabase project identity, admin/Owner login, and role boundaries. **Done when:** the live URL serves the approved commit and connects only to production.
- [ ] **R21 — Install and activate the production POS** (Developer + System administrator + Cashier; needs R14 and R20). Set up the reviewed real accounts, catalog, prices, and opening stock. Install the exact signed production APK on the chosen phone without discarding any existing queue; activate its one-stall binding and verify product/stock pull. **Done when:** package, certificate, version, device binding, and opening stock match the release record.
- [ ] **R22 — Smoke-test live operation** (Developer + System administrator + Cashier; needs R21). Check mobile IMS and Owner access, operating-day open, a controlled real sale, exactly-once sync, and matching IMS figures. **Done when:** all checks pass, the sale is treated as a real auditable production record, and starting cash/stock is reconciled.
- [ ] **R23 — Supervised one-stall pilot** (Owner + Cashier + Developer; needs R22). Run at least three consecutive fully reconciled operating days. At each close compare receipts, gross sales, deductions, expected versus counted cash, stock movements, and Owner reports. Use R18's paper fallback and preserve the phone if a serious discrepancy or queue failure occurs. **Done when:** all three days reconcile and Owner/Cashier sign off; unresolved discrepancies remain blockers regardless of duration.
- [ ] **R24 — Close out and establish routine releases** (Developer + Owner; needs R23). Tag the source commit, retain web/APK artifacts and checksums, backup reference, migration record, UAT/pilot sign-offs, support procedure, and next-release backlog. **Done when:** the operational release record is complete and the next change can follow the feature → development → acceptance → production process.

## Release baseline

Fill this in during R01; do not infer production values from local files.

| Item | Current verified value / evidence |
| --- | --- |
| Local branch and commit | `codex/inventory-recipes-ui` at `a5203d0` in the 4 October local check; many working-tree changes remain |
| Changed/untracked paths | 125 on 2026-10-04: Android 64, web 22, Supabase 26, docs 9, root/other 4. These are status paths, not 125 reviewed changes. |
| Credential-bearing local paths | `.env.development.local`, `.env.local`, `local.properties`, and `supabase/.temp` exist and are Git-ignored. Values were not read or staged. The staged files were screened for credential patterns during R03. |
| Preserved source commits | `02cedcf` environment setup; `fd82002` Supabase contracts/tests; `9b99d3f` Android POS/Room/UI; `6dc1811` IMS source/tests; `9f41023` release documents. All are on `codex/inventory-recipes-ui` and have not been pushed. |
| Source verified for R03 | `9f41023`: 45 web tests, web lint, and `build:development` passed; 84 Android `testDevDebugUnitTest` cases passed with zero failures/errors. This is a local verification, not live database or device acceptance. |
| Preserved unrelated file | Root `Screenshot_1790765438.png` shows an Android USB debugging prompt. It remains untracked and was not included in any commit. |
| Remote default/main state | `origin/main` freshly fetched and verified at `c90835f` during R04 |
| `develop` branch / CI | Local and remote `develop` created at `c90835f`; [draft PR #1](https://github.com/LemorDev/Ice-Cream-System/pull/1) targets it. Both branches require passing `Web checks` and `Android checks`; [run 37280302762](https://github.com/LemorDev/Ice-Cream-System/actions/runs/37280302762) passed both at `d39525e`. [Branch flow](BRANCH_REVIEW_FLOW.md) records protection and deployment settings. |
| Development Supabase schema | First applied 3 October per [Supabase setup guide](../supabase/README.md); check current pending migrations |
| Production Supabase project and migration history | Unknown; verify read-only in R01/R15 |
| Currently deployed web URL and revision | Unknown; verify in R01/R15 |
| Installed production POS package, version, certificate, queue | Unknown; verify in R01/R15 |
| Approved v1 scope and UAT testers | [Scope revision `0bba1be`](FIRST_RELEASE_SCOPE.md) accepted by Owner and Cashier as reported by user on 2026-10-05; UAT tester names pending R11 |
| Backup and recovery target | Pending R16 |

## Progress log

| Date | Task | Result / evidence | Next action |
| --- | --- | --- | --- |
| 2026-10-04 | Planning | Created the numbered tracker from the deployment plan; checked local branch, dirty working tree, and absence of local `develop`/`.github` workflows. | R01: inventory and sort current changes. |
| 2026-10-04 | R01 done | Grouped 125 status paths and checked that local credential/config paths are ignored. Production web, database, and installed POS details remain explicitly unknown pending production access. | R02: agree on v1 scope and acceptance rules. |
| 2026-10-05 | R03 done | Reviewed path inventory and staged content, screened for credential patterns, excluded ignored local configuration and the unrelated screenshot, and saved five grouped commits (`02cedcf` through `9f41023`). Web tests/lint/development build and Android unit tests passed on `9f41023`. | R02 remains next for Owner/Cashier scope decisions; R04 is independently ready after R03. |
| 2026-10-05 | R02 in progress | Drafted the [scope and acceptance rules](FIRST_RELEASE_SCOPE.md). Planning choices are a tested POS refund/void flow, midnight-spanning operating day, original-day refund correction by the Cashier without prior approval, numbered-paper outage sales, and the proposed defaults for the remaining workflows. Current POS reversal and cross-midnight reporting are unverified release blockers. | Obtain Owner and Cashier acceptance of the same scope revision; detail the paper sheet and support contact in R18. |
| 2026-10-05 | R02 done | User reported that Owner and Cashier both accepted scope revision `0bba1be`; acceptance recorded in the scope document. This is workflow agreement, not a passing implementation test. | R04 branch/review flow; R05 audits the known refund and midnight release blockers. |
| 2026-10-05 | R04 in progress | Fetched remote `main` at `c90835f`, created/pushed `develop` at the same commit, pushed feature head `65284df`, and opened [draft PR #1](https://github.com/LemorDev/Ice-Cream-System/pull/1) into `develop`. User chose public visibility to enable GitHub Free branch protection; both long-lived branches now require PRs and disallow force pushes/deletions. User reports no hosting connected; no deployment workflow exists. [Review flow](BRANCH_REVIEW_FLOW.md) and initial PR checks added. | Run the checks, require their verified names on both branches, and verify final rules before closing R04. |
| 2026-10-05 | R04 done | Verified remote `main` and `develop` at `c90835f`, PR #1 base `develop`, and branch protection on both long-lived branches: PR required, strict `Web checks` and `Android checks`, admin enforcement, no force push/deletion. Both checks passed at `d39525e` after fixing the Android setup action. User reports no hosting connected, and the only GitHub workflow has no deploy job. See [branch/review evidence](BRANCH_REVIEW_FLOW.md). | R05 risk audit; R07 adds isolated database CI and makes it required before candidate merge. |
