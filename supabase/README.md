# Supabase

## Apply the initial database schema

1. Create a Supabase project and open **SQL Editor**.
2. Open `migrations/202608040001_initial_schema.sql` from this folder, copy its contents, and run it once.
3. After running the migration, uncomment the `create_initial_manager` command at the bottom, change its email and password, and run that command. Use a strong password.
4. In **Project Settings → API**, copy the Project URL and the **anon/publishable** key into `apps/web-ims/.env.local`. Never use the `service_role` key in a browser app.
5. Start the web application with `pnpm --filter ice-cream-ims dev`, then sign in using the manager credentials created in step 3.

## Custom password security

This project does not use Supabase Auth providers. The `login_with_password` database function verifies a bcrypt-hashed password and returns a random, short-lived session token. The dashboard stores that token in browser session storage, not the password. Each Supabase request sends the token in an `X-Session-Token` header. RLS validates it, then limits data to the signed-in user's assigned stall.

## Role-based access rules

These are the access rules the schema and RLS policies are built around:

- `manager`
  - Can manage products, categories, inventory receiving, price changes, daily closures, and device records for their stall.
  - Can view users in their own stall.
  - Can create, update, and soft-delete operational records tied to their stall.
  - Can create and reverse sales through the business logic helpers when the app needs a stock adjustment trail.

- `cashier`
  - Can view the catalog, own-stall inventory state, and sales data needed for checkout.
  - Can create sales and transaction items for their own stall.
  - Can read their own stall data only.
  - Cannot manage master data such as products, categories, devices, or user accounts.

Shared rules:

- Every request is scoped to one stall through the signed-in session.
- No user can read or mutate records from another stall.
- No client app should use elevated keys or bypass the session-token flow.

For production, add login rate limiting and a password-reset process before deploying.
