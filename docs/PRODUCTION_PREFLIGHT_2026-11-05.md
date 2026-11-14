# Production preparation: initial audit

Date: 5 November 2026 (Asia/Singapore). The user selected a separate production environment for the intended first real-sales operation on 6 November. The user confirmed the retained production project's existing records are old test data only.

## Verified observations

- Connected phone: only `com.icecreampost.pos.dev`, version `0.1.0-dev`, versionCode 1; last updated 4 November. A local Room database and WAL exist. No APK was installed or app data reset during this audit. Pending phone records were not yet inventoried.
- Inspected retained production project `hdtizzdrbscchvqwcpkb`, using the pinned CLI. Its remote CLI migration history is empty, but its application schema exists. A dry run proposes all 62 repository migrations, including the non-idempotent initial schema. No migration was applied.
- Exact read-only production counts: 1 stall, 3 users, 3 devices, 29 products, 32 transactions, 120 inventory ledger rows, 13 audit rows, and zero business days, daily store closings or revenue deductions. These counts include retained/soft-deleted rows and are not a reconciliation of active balances.
- No Edge Functions are deployed in that retained production project.
- A schema export attempt to a Git-ignored directory failed because neither Docker nor Podman is installed. No completed database backup or restore test was produced. Do not treat the partial export location as a valid backup.
- Restored and verified the root CLI link to development `fhyqrgxwdqlyzxsnpthr` after the production history/dry-run inspection. Later read-only queries used explicit production project references.

## Migration review

The existing production schema cannot safely receive an unconditional replay of the initial migration. Do not mark all migrations applied merely to bypass this conflict. Reset-named migrations primarily define callable functions, but other migrations perform real data backfills and identifier/unit conversions. The sale cost/day backfill explicitly assumed disposable development data. Historical catalog and device policy upgrades require queue reconciliation and matching clients.

## Proposed next step

Because the user confirmed the retained records are test-only, recommend a fresh production Supabase project while keeping the old test project paused. Free accounts allow two active projects; paused projects do not count. The current development project stays active. Paused-project restoration is time-limited and is not a substitute for a tested long-term backup.

No project was paused, deleted or created by this audit. Obtain the new production reference before configuring any production credentials or migration targets. Update the development isolation comparison to that new reference when it is known. Do not copy development sessions, devices, test sales or test stock into production.

After clean-project initialization, still required before real sales: production web hosting and passkey origin/enrollment, real catalog and counted opening stock, staff accounts, a signed production APK and device activation, verified backup/recovery, and an end-to-end physical-phone acceptance run. Tomorrow's target date does not certify readiness. Until these checks pass, continue the documented paper fallback rather than recording real sales in development.
