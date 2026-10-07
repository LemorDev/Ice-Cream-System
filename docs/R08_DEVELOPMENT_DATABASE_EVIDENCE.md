# R08 — development database contract

Status: complete, 7 October 2026. Development migration history, database
contract, and HTTP RPC checks passed. The production project was not contacted.

## Target and candidate

- R06 candidate source: `5cb013ef939a28e4206ade265918e6ca92b23f55`.
- R08 smoke checks: `9b73c94`; the applied throttle migration compatibility
  edit and authenticated HTTP check: `3a7bc88`. Both are on
  `codex/inventory-recipes-ui` in draft PR #1.
- Documented development project reference: `fhyqrgxwdqlyzxsnpthr`.
- The ignored `supabase/.temp/project-ref` and `linked-project.json` both match
  that reference. The ignored `apps/web-ims/.env.development.local` has a URL
  for that reference and `VITE_APP_ENV=development`. No key was printed or committed.
- Pinned Supabase CLI: `2.118.0`.

## Disposable database

`node supabase/tests/pos_stock_receiving.integration.mjs --extended --activation`
passed on 7 October 2026. It applied the repository migrations to a fresh,
in-memory PGlite database, then passed the rollback-only fixtures for receipt
replay, stock classification/counts, login limits, sale replay, paged catalog,
sale snapshots and reconciliation, two-stall R08 contract, recipes/closings,
activation, deductions, data reset, and administrator close. No cloud database
was contacted by this runner.

## Development project verification

| Check | Result |
| --- | --- |
| `migration list --linked` | Passed: 30 prior migrations matched local history; 13 reviewed October 5–7 migrations were pending. |
| `db push --dry-run --linked --skip-vault` | Passed: exactly those 13 migrations; no seeds or roles. |
| Apply reviewed migrations to development only | Passed: all 13 applied with `--linked --project-ref fhyqrgxwdqlyzxsnpthr --skip-vault`; no seed, role, or vault changes. |
| Recheck migration history and dry run | Passed: all 43 local migrations match remote history; dry run reports `upToDate: true` and zero pending files. |
| `db query --linked --project-ref fhyqrgxwdqlyzxsnpthr --file supabase/tests/r08_development_contract.sql` | Passed: two stalls, role restrictions, exact and conflicting replay, and matching POS/IMS fields. This SQL transaction ends in `ROLLBACK`. |
| Confirm zero persisted R08 SQL rows | Passed: `r08_stalls_remaining = 0`. |
| `node supabase/tests/r08_http_contract.mjs` | Passed: nine matching POS/IMS RPC endpoints exist and deny anonymous access. |
| `node supabase/tests/r08_authenticated_http.mjs` | Passed: two Cashiers saw only their own stall; a POS sale was accepted once, exact replay returned duplicate, changed replay returned conflict; Owner snapshot matched receipt and COGS, while other-stall and Cashier financial reads were denied. Cleanup ran in `finally`. |
| Confirm zero persisted R08 HTTP rows | Passed: `r08_http_stalls_remaining = 0`. |

The development SQL smoke checks the POS product, recipe, and ledger pages; a
sale accepted once with booked day and COGS; an identical replay; a changed
replay conflict; cross-stall rejection; Owner snapshot child rows; and Cashier,
Owner, and anonymous restrictions. The HTTP checks confirm that the matching
PostgREST RPC endpoints exist, deny unauthenticated use, and honor the POS and
Owner request headers and response shapes. Physical Android and web user flows
remain R09–R12.

## Migration review before push

The R06 candidate's October 6–7 migrations add replay identity, session
revocation, paged POS pulls, booked sale components/day/cost, reconciled
closing, day-bound stock and deductions, full POS reversal, a stall-scoped IMS
snapshot, and late-sale correction. The one existing-data backfill in
`202610060004_sale_day_cost_snapshots.sql` assigns a best-effort day and
current unit cost to earlier development records; it is not accepted as
historical UAT evidence. `202610070006_retire_legacy_financial_rpcs.sql`
revokes old financial write RPCs, so the matching R06 clients must be used
after the migration series. No migration in this set truncates or resets the
database. The exact pending list was `202610050001`, `202610060001`–`202610060004`,
and `202610070001`–`202610070008`.

The first push stopped at `202610050001`: development already had
`private.password_login_failures`, but CLI history did not record its migration.
Read-only inspection found its four expected columns, primary key, foreign
key, positive-count check, zero rows, and login RPCs that call the limiter.
The migration now uses `CREATE TABLE IF NOT EXISTS`, still redefines the
functions and grants, and passed the fresh disposable suite. The second dry
run listed the same 13 files; the second push applied all of them. No later
migration was attempted during the failed first push.

The rollback-only regression fixtures ran on an in-memory disposable database.
The separate R08 development SQL smoke explicitly targeted the development
project and rolled back; the HTTP smoke removed its synthetic rows. No test
fixture or migration command targeted production.
