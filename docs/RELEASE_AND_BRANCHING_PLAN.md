# First operational release and branching guide

Prepared 20 September 2026. This is a proposed rollout plan, not a deployment approval or a claim of production readiness.

## Current starting point

- The inspected local checkout is on `codex/inventory-recipes-ui`, tracking the same remote branch. `main` also exists locally and in the cached remote refs. Remote refs were not refreshed during planning.
- There are uncommitted Android, web, database, and test changes. Preserve and review them before switching branches or preparing a release.
- No `.github` workflow directory was found. Any hosting-provider auto-deployment settings still need inspection; a push to `main` does not inherently deploy anything.
- Android has `dev` and `production` flavors, version code 1 / version name 0.1.0. Release signing is not configured in the inspected Gradle file.
- The previous implementation run passed 48 Android JVM tests, 29 web tests, and the web build. These were not rerun for this planning task. The Android repository tests use mocked storage/API boundaries; they do not verify a real Room upgrade or a live backend.
- SQL fixtures, physical-device recovery, and the signed production build remain unverified. The September 8 project review is useful background, but some findings are outdated; re-audit each before treating it as open or resolved.

## Understand the terms

| Term | Meaning in this project |
| --- | --- |
| Branch | A named line of code history. Creating one does not create a server or database. |
| Commit | A saved set of changes in local Git history. |
| Push | Upload commits to GitHub; automation may then run if configured. |
| Pull request (PR) | A proposed merge from one branch into another, with a diff and test results. |
| Environment | A running app configuration plus its database, accounts, and credentials. |
| Deployment | Installing a selected app build or applying selected backend changes to an environment. |
| Tag | A fixed label, such as `v1.0.0`, identifying the source commit for a release. |

## Proposed branch and environment flow

Use `develop` and `main` as the two long-lived branches. Development and production are the only deployed environments; feature branches are temporary.

```text
codex/<feature> --PR--> develop --release PR--> main
                       development testing   production release
```

| Branch | Purpose | Deployment policy |
| --- | --- | --- |
| `codex/<feature>` | One feature or fix, created from `develop` | Run tests; optional preview using test data |
| `develop` | Features integrated and tested before release | Development Supabase project and development builds only |
| `main` | Code approved for real operations | Explicit release action initially; production Supabase and builds only |

Development uses test data. Real customer sales belong in production, even during a supervised pilot. Do not use production data for destructive tests.

Use the dedicated development Supabase project and the separate production project. Separate URLs/keys and unmistakable app names prevent accidental cross-environment sales. The development Android flavor has a distinct application ID; keep the production application ID stable.

Keep `develop` as the integration branch. Test a release candidate against the development project, then promote the reviewed commit to `main`. Fixes made directly for production should be merged back into `develop` so the next release retains them.

Use merge commits for promotions between long-lived branches to preserve their shared history. Feature PRs can be squash-merged. Do not require linear history if promotion PRs use merge commits.

## Establish the workflow safely

1. Review the current changes with `git status` and `git diff`. Group them into understandable commits on the existing feature branch; inspect untracked files as well. Do not indiscriminately stage credentials, signing keys, or unrelated files.
2. Fetch the current remote state. Create `develop` from the reviewed remote `main` baseline. Push the branch deliberately; first inspect hosting integrations so it cannot unexpectedly deploy to production.
3. Open a PR from the existing `codex/inventory-recipes-ui` branch into `develop`, including reviewed current changes. Resolve conflicts on the feature branch and run checks before merging. No history rewriting is needed.
4. Configure protections for `main` and preferably `develop`: require PRs and passing checks; block force pushes and branch deletion. If another reviewer is available, require their review. A solo maintainer can use PRs and required checks without a review requirement they cannot satisfy themselves.
5. Configure test credentials separately. Keep signing passwords, private keys, database credentials, and service-role keys out of Git and client bundles.
6. Configure production deployment as an explicit release action. Restrict it to approved source refs and credentials. GitHub protection/approval availability depends on the repository visibility and account plan; verify available controls before relying on them.

The commands below are teaching examples, not commands already executed. Run setup only after current edits are committed and `git status` is clean. Stop on any error rather than continuing the block blindly.

```powershell
# One-time setup: after reviewing remote main as the intended baseline.
git fetch origin
git switch -c develop origin/main
git push -u origin develop
git switch codex/inventory-recipes-ui
# In GitHub: open a PR with base=develop, compare=codex/inventory-recipes-ui.
```

If a branch already exists, switch to it instead of recreating it. Do not reset it to match this example.

```powershell
# Normal feature work, after the initial setup is complete.
git switch develop
git pull --ff-only origin develop
git switch -c codex/cashier-deduction-history
# Edit, inspect the diff, test, and git add only the intended paths.
git commit -m "Add cashier deduction history"
git push -u origin codex/cashier-deduction-history
# In GitHub: open a PR with base=develop, compare=the feature branch.
```

Select both the PR base and comparison branch explicitly. GitHub may default to `main`; change the base to `develop` for normal features.

For a release: test `develop` against the development project, then open a reviewed PR into `main`. Record the resulting commit and create the release tag only after release checks pass. Build and retain the exact production artifacts from that approved source. The production artifact still requires a smoke test before distribution.

## Release phases and completion gates

### 1. Baseline and release scope — developer

- Record the actual deployed web revision, database migration history, installed phone package/version/signing certificate, and whether the phone already contains real or unsynced records.
- Decide the first-release scope: one stall, one active POS, opening/closing rules, Manila business-date cutoff, and the supported refund/deduction workflow.
- Define a deduction precisely. The current feature captures one aggregate amount/reason at closing and reduces expected cash and profit. It is not an itemized expense ledger or a sale reversal. Avoid deducting refunds or spoilage twice when already reflected in reversed sales or waste costs.
- Resolve whether the cashier needs multiple entries during the shift, manager approval, correction after closing, and owner-visible deduction details. Treat missing essential behavior as release work.

Gate: reviewed source snapshot, written scope, and no unidentified live data on a device about to be replaced or reinstalled.

### 2. Fix and verify release blockers — developer

- Run all SQL fixtures in a disposable database with all migrations applied in order. Audit migration bodies before execution, especially files named for data resets: determine whether they define reset helpers or perform resets. Never execute reset operations against live history during a release.
- Re-audit account/stall isolation, device authorization, cloud row limits/pagination, retry idempotency, simultaneous background/manual sync, report dates, historical costs, and the previous review's remaining findings.
- Verify closing RPC authorization for cashier, stall, device, and business day, plus amount/reason validation and replay behavior.
- Verify deduction compatibility: an older closing RPC accepts JSON and may ignore new fields while returning success. Deploy and verify the new server contract before the client; add a compatibility check or acknowledgement that prevents silent loss of deductions.
- Verify closing totals after failed sale uploads: a closing must not be accepted as final while its sales remain unsynced or rejected. Confirm missing-RPC handling cannot display a misleading all-synced state.
- Trace deduction amounts through owner reports; verify displayed net revenue/profit matches persisted closings rather than a separate calculation that omits deductions.
- Verify custom session security, login rate limiting, recovery, role access, and logs that must not contain tokens/passwords.

Gate: no unresolved issue that can lose, duplicate, leak, or misstate sales, deductions, stock, or cash. Record evidence and a decision for every item above.

### 3. Automated checks and development rehearsal — developer + cashier

Add CI checks on PRs: web tests/lint/build, Android JVM tests/build, and SQL fixtures against an isolated database. Publish test results and build artifacts. Add instrumented tests for actual Room migration and durable queue behavior.

| Test category | Required scenarios | Passing evidence |
| --- | --- | --- |
| Functional | Open day, sell, cash/change, close, valid/invalid deductions, cancellation, re-entry after an error, smallest phone screen/keyboard | Cashier completes each workflow; invalid submissions cannot alter records |
| Database integration | Fresh install and upgrade; roles/two stalls/revoked devices; deduction validation; duplicate requests | SQL fixtures pass; unauthorized changes rejected; accepted values match request |
| Device/backend integration | Offline sales, force-stop/restart, reconnect, timeout after server commit, expired session, simultaneous sync, pending sales at close | Every accepted sale exists exactly once; queue clears only after acknowledgement |
| Upgrade/regression | Actual Room v4→v5 with pending sales/closings; no-deduction legacy closing; recipes/waste/restock; midnight and large history | Data preserved; inventory, dates, and cash match independently calculated totals |
| Reporting | Deductions and costs across daily totals, owner views, exports and zero-sales days | Cashier and owner reconcile to the same records |
| Recovery | Restore a database backup in isolation; interrupted rollout; signed app update | Documented recovery works without deleting unsynced device records |

Use exact expected amounts in fixtures. Example: gross sales PHP 200, COGS PHP 50, waste PHP 10, overhead PHP 20, deduction PHP 15 => expected cash PHP 185 and net profit PHP 105. Collected cash is the actual count and can differ from expected cash; show the variance separately.

Gate: all critical scenarios pass on the actual model of phone, with real Room storage and development Supabase. JVM tests alone do not satisfy this gate.

### 4. Prepare production and recovery — developer/administrator

- Configure release signing, securely back up the signing key, and keep it stable for subsequent updates. Increase `versionCode` for each distributed update and set a meaningful version name.
- Check the installed app's identity/certificate before planning an in-place upgrade. A `.dev` package is a separate app, and changing a signing key can prevent updates. Reconcile and preserve pending data before any transition; do not uninstall to solve an upgrade problem.
- Choose distribution: managed APK installation for a small internal pilot or a suitable Play distribution track. Verify current signing/distribution requirements for that route.
- Prepare production Supabase, HTTPS web hosting, actual products/recipes/prices/opening stock, roles, device activation, and confirmed environment URLs. No test sales in the real ledger.
- Define database backup frequency and recovery targets with the owner. Back up before migrations; prove restore to an isolated project. Cloud backups cannot recover sales that never left the phone.
- Write a cashier fallback sheet: paper receipt sequence, sale time/items/cash, deduction amount/reason, person to call, and how records will later be reconciled without duplicates.
- Retain the previous web artifact, signed Android artifact, schema compatibility notes, and release record. Android downgrade is not a reliable rollback, especially after a Room schema upgrade; prepare a compatible forward-fix build.

Gate: reproducible signed build, recoverable backups, successful update rehearsal, and someone available to handle the pilot.

### 5. Deploy and run a supervised pilot — developer + owner + cashier

1. Select a quiet period, sync and reconcile the current device, record pending counts, and take a verified backend backup.
2. Apply only reviewed pending database migrations, then verify their contracts with safe checks. Never run the fixture suite against production.
3. Deploy the matching web build and install the approved signed Android build. Verify environment, login, device activation, catalog, and opening stock before accepting sales.
4. Observe the first shift at one stall. Record a starting cash count and physically count closing cash and key inventory. Compare every receipt and deduction with the owner dashboard.
5. Repeat for at least three consecutive fully reconciled operating days, including a controlled offline/reconnect exercise performed in development before production. Duration alone is not evidence: require correct counts/totals and no unresolved queue failures.
6. Sign off with the cashier and owner before routine unattended use. Keep daily reconciliation and an escalation contact afterward.

Stop the pilot for missing/duplicate sales, unexplained cash/stock differences, unauthorized access, crash loops, or a queue that cannot recover. Preserve records, switch to the paper process, identify the last acknowledged transaction, and diagnose before resuming. Restore data only with an explicit reconciliation plan for sales accepted since the backup.

## Production fixes after release

Create `codex/hotfix-<issue>` from current `main`. Reproduce and test the fix, review its PR to `main`, and release deliberately. Immediately merge the fix back into `develop` so the next release cannot undo it. Keep a release record with commit/tag, migrations, web artifact, APK version/checksum, test evidence, backup reference, and rollout outcome.

## Suggested next implementation batch

1. Review and commit the current feature work, establish branches, protect merges, and inspect current hosting triggers.
2. Add CI and run database and real-device acceptance tests against development.
3. Resolve the release-blocking findings, configure signing and backups, and rehearse an upgrade.
4. Schedule the supervised pilot only after the gates above pass.

## Reference documentation

- [GitHub deployment environments](https://docs.github.com/en/actions/concepts/workflows-and-actions/deployment-environments)
- [GitHub deployment protection and environment reference](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
- [Android release preparation](https://developer.android.com/studio/publish/preparing)
- [Android app versioning](https://developer.android.com/studio/publish/versioning)
- [Android app update identity and signing](https://developer.android.com/google/play/app-updates)
