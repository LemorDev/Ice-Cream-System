# Supabase

## CLI connection for development

Use only the **development** and **production** environments. The development
Supabase project is `fhyqrgxwdqlyzxsnpthr`; production has a different project
reference. Keep URLs and publishable keys in ignored local configuration files.
Never put a CLI access token, database password, or secret/service-role key in
chat, source files, or Git.

From the repository root, authenticate the pinned CLI locally and explicitly
link the development project:

```powershell
$env:SUPABASE_TELEMETRY_DISABLED = '1'
.\node_modules\.bin\supabase.cmd login
.\node_modules\.bin\supabase.cmd link --project-ref fhyqrgxwdqlyzxsnpthr
.\node_modules\.bin\supabase.cmd migration list --linked
.\node_modules\.bin\supabase.cmd db push --dry-run
```

Confirm the linked project reference is the development project and the preview
contains only the expected pending migrations before running
`.\node_modules\.bin\supabase.cmd db push`. The development schema was first
installed from all repository migrations on 2026-10-03. Never use
`db reset --linked`; rollback-only fixtures in `tests/` are for a disposable
database, never a live sales database. The linked project reference is kept in
ignored `supabase/.temp/` files.

On 2026-10-07, the development project matched all 43 repository migrations
through `202610070008_late_sale_reconciliation.sql`; a fresh `db push --dry-run`
showed no pending files. The [R08 evidence](../docs/R08_DEVELOPMENT_DATABASE_EVIDENCE.md)
records the development-only contract checks. Production migration history is
still a separate release gate.

## Apply the database schema

1. Apply migrations to the development project using the CLI workflow above. The push includes no seed data.
2. In the development project's **SQL Editor**, create the first System admin and active stall by running the following as the database owner with your own values:

   ```sql
   select public.create_initial_system_admin(
     'Main Ice Cream Stall',
     'MAIN-001',
     'admin@example.com',
     'CHANGE-THIS-TO-A-STRONG-PASSWORD',
     'System Administrator'
   );
   ```

   Client roles cannot execute this bootstrap helper. From the web dashboard, the System admin can then create the regular Owner and Cashier accounts.
3. Start the web application with `pnpm --dir apps/web-ims dev`, then sign in with the System admin account. Create test-only Owners, Cashiers, products, and inventory from development IMS.

Never ship a `service_role` or secret key to either client. Keep development and production URL/key pairs separate in their ignored local configuration files.

For an existing database, the role migration automatically renames every `manager` account to `owner` and gives it access to its existing stall. Promote one chosen account to System admin after applying all migrations.

## Custom password sessions

This project does not use Supabase Auth providers. The web uses `login_with_password`; Android uses `login_pos_with_password`, which also verifies the stall code and recognizes an already activated device from `X-Device-Id`. Both return a random, short-lived session token after bcrypt password verification. The web dashboard stores the token in browser session storage. Android stores its session and restored device binding in Room so an activated POS can continue operating offline. Each API request sends the token in `X-Session-Token`; database policies and secured functions resolve the user from that token.

## Role-based access

- `system_admin` can access every stall and owns all web management operations: stalls, products, prices, costs, overhead, inventory, Owner and Cashier accounts, Owner assignments, transaction correction, and POS activation.
- `owner` has read-only web monitoring for assigned stalls: revenue, profit, sales activity, product performance, and opening/closing history. Owners cannot mutate operational data or access an unassigned stall.
- `cashier` can sign in to an assigned Android POS, open and close its operating day, make sales while that day is open, and sync the cost-free POS catalog and own-stall inventory. Cashiers cannot use the Owner dashboard or read product costs and profit data.

One POS device can be active for a stall at a time. `MAIN-001` is a stall code used during sign-in, while the System Administrator-generated POS code is a separate one-time activation credential. The code is stored only as a hash, works once, and is bound to the Android hardware identifier when redeemed. First activation codes expire after 24 hours; replacement codes expire after 30 minutes to match the old phone's prepared handover window. Subsequent Cashier sign-ins on that phone restore the active binding without asking for another activation code.

For a planned replacement, close the operating day on the old phone, sync until every queued sale, stock change, deduction, and closing is accepted, then use **Device information → Prepare for replacement**. The System Administrator can create a code during the following 30 minutes. Redeeming it deactivates the old phone; inactive devices cannot upload new sales or deductions. Signing in again on the old phone cancels its preparation, so repeat the steps if needed. A different operating-day ID for a date already on the server is rejected rather than silently accepted.

If the old phone may still be recovered, connect it and sync before authorizing replacement. If it is lost, the System Administrator can authorize recovery in **Users & access → Cashier POS activation** and record why. The replacement resumes an open server day using its existing day ID and displays the sales, deductions, COGS, and waste known to IMS at recovery time. The cashier counts the cash and receipts physically, records the actual closing count, then the administrator records a reconciliation review in IMS. Recount physical stock and enter adjustments for missing stock movements.

The Android queue is stored locally in Room. Sales, deductions, stock receipts, or other records that existed only in the lost phone's queue cannot be recovered by IMS; recovery marks the incident for review and preserves known server data, but does not recreate unknown records or declare them synced. If the phone is later found, connect and sync it before activating the replacement. If activation already happened, the old phone is disabled and its old queue needs a separate controlled reconciliation; do not discard or reset it.

POS deductions lower expected cash. Only deductions marked **Additional expense in profit** lower profit again; cash used to pay a fixed IMS overhead expense should leave that option off. Apply `202610010002_pos_activation_and_deduction_alignment.sql` before releasing the matching Android and web builds, or deduction accounting and replacement controls will disagree between clients and server.

The current business has one active stall. The schema and Owner dashboard already support assigning several stalls without changing the one-stall workflow.

The password login RPCs share a per-account limit: after five incorrect passwords within fifteen minutes, both web and POS sign-in return no session for fifteen minutes, even with the correct password. Unknown accounts and wrong stall codes return the same no-session result. The counter is stored in the private schema and is cleared after a successful sign-in. Apply `202610050001_limit_password_login_attempts.sql` to each environment before exposing a release; client builds alone cannot enforce this protection. An administrator should help a locked-out user wait for the cooldown and then reset the password using the existing managed-user workflow. A dedicated account recovery process remains a release task.

## Migration verification

The rollback-only SQL fixtures in `tests/` cover sale/reversal behavior and the RBAC, stall assignment, device activation, operating-day, and deduction contracts. Run them only against a disposable database after applying every migration. The PGlite regression runner can exercise the activation and deduction fixtures locally with `node supabase/tests/pos_stock_receiving.integration.mjs --activation`; still validate the migrations on a development Supabase project before production.

Before production, rehearse account recovery with the actual staff, verify the
production migration history separately, and complete the release checks in
`docs/PROJECT_REVIEW.md`.
