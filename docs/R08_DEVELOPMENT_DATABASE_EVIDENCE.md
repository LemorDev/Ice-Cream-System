# R08 — development database contract

Status: in progress, 7 October 2026. Do not mark R08 complete until the remote
history, development migration push, development contract smoke, and HTTP RPC
checks below have recorded results. The production project is out of scope.

## Target and candidate

- Candidate source before R08 evidence work: `5cb013ef939a28e4206ade265918e6ca92b23f55` on `codex/inventory-recipes-ui`.
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
| `migration list --linked` | Pending: CLI login is required in this session. |
| `db push --dry-run` | Pending: compare every pending filename to the reviewed source. |
| Apply reviewed migrations to development only | Pending. Do not include seed data. |
| Recheck migration history and dry run | Pending. |
| `db query --project-ref fhyqrgxwdqlyzxsnpthr --file supabase/tests/r08_development_contract.sql` | Pending. The SQL uses synthetic two-stall data and ends in `ROLLBACK`. |
| Confirm zero persisted R08 rows | Pending. |
| `node supabase/tests/r08_http_contract.mjs` | Pending. Uses only the ignored development publishable key and sends anonymous denial checks. |

The development SQL smoke checks the POS product, recipe, and ledger pages; a
sale accepted once with booked day and COGS; an identical replay; a changed
replay conflict; cross-stall rejection; Owner snapshot child rows; and Cashier,
Owner, and anonymous restrictions. The HTTP smoke checks that the matching
PostgREST RPC endpoints exist and deny unauthenticated use. Physical Android
and web user flows remain R09–R12.

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
database. Confirm the exact pending list from the remote history before push.
