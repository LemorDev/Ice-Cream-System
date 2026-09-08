# Supabase

## Apply the database schema

1. Create a Supabase project and open **SQL Editor**.
2. For a fresh database, apply every SQL file in `migrations/` once, in filename order. For an existing database, apply only migrations that have not already been applied.
3. Create the first System admin and active stall by running the following as the database owner with your own values:

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
4. In **Project Settings → API**, copy the Project URL and **anon/publishable** key into `apps/web-ims/.env.local` and the Android `local.properties`. Never ship the `service_role` key to either client.
5. Start the web application with `pnpm --filter ice-cream-ims dev`, then sign in with the System admin account.

For an existing database, the role migration automatically renames every `manager` account to `owner` and gives it access to its existing stall. Promote one chosen account to System admin after applying all migrations.

## Custom password sessions

This project does not use Supabase Auth providers. The `login_with_password` database function verifies a bcrypt password and returns a random, short-lived session token. The web dashboard stores the token in browser session storage. Android stores it in Room so an already activated POS can continue operating offline. Each API request sends the token in `X-Session-Token`; database policies and secured functions resolve the user from that token.

## Role-based access

- `system_admin` can access every stall, create stalls, maintain stall names and codes, create or update Owner and Cashier accounts, assign Owners to several stalls, manage POS activation, and perform operational support.
- `owner` can use the web dashboard for assigned stalls, including revenue and profit reports, products, pricing, costs, overhead, inventory, Cashier accounts, and POS activation. Owners cannot create Owners, System admins, or stalls, change stall identity, or access an unassigned stall.
- `cashier` can sign in to an assigned Android POS, open and close its operating day, make sales while that day is open, and sync the cost-free POS catalog and own-stall inventory. Cashiers cannot use the Owner dashboard or read product costs and profit data.

One POS device can be active for a stall at a time. Redeeming a replacement activation code deactivates the previous device. The code is stored only as a hash, works once, and is bound to the Android hardware identifier when redeemed.

The current business has one active stall. The schema and Owner dashboard already support assigning several stalls without changing the one-stall workflow.

## Migration verification

The rollback-only SQL fixtures in `tests/` cover sale/reversal behavior and the RBAC, stall assignment, device activation, and operating-day contracts. Run them only against a disposable database after applying every migration. They have been prepared locally but were not executed during this implementation because no local PostgreSQL runtime is configured.

Before production, also add login rate limiting and an account recovery process, validate the migrations against a development Supabase project, and complete the release checks in `docs/PROJECT_REVIEW.md`.
