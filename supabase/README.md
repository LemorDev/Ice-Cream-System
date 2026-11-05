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

The web uses `login_with_password`. Android uses `login_pos_with_password` and verifies the stall code, employee ID, and device key. Both return a short-lived custom session bearer after password verification. The web stores it in browser session storage. Android stores it in an AES-GCM credential file under Android Keystore protection, and Room holds session metadata and the offline queue. Both clients revoke the current bearer on sign-out. When the server is unreachable, Android clears local access and retries the revocation from an encrypted queue until the session expires. Web sign-out clears local access, retries once in memory, and reports unconfirmed revocation on the sign-in screen.

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

Both password login RPCs enforce a per-account limit of five failed attempts within 15 minutes, followed by a 15-minute cooldown. Account cooldowns, unknown accounts, and incorrect credentials return the same no-session result. Successful password sign-in clears the account's failures. An administrator can reset a managed user's password to clear the cooldown and revoke existing sessions.

Apply `202611040001_harden_password_sign_in.sql` before deploying the matching clients. It also limits the two RPCs to 30 total attempts per minute from a single IPv4 address or IPv6 /64, including attempts against unknown accounts. Excess requests receive HTTP 429 with a `Retry-After` header. The network limit uses `CF-Connecting-IP` supplied by the hosted Supabase gateway. Direct SQL and local requests without that header still use the account limit.

New and reset passwords for web and POS accounts require 15–128 Unicode characters. The server rejects common, repetitive, and account-derived choices using local checks. Staff set and reset Cashier passwords in the web dashboard's **Users & access** screen. The System Administrator bootstrap function applies the same policy. Existing passwords continue to verify with their stored bcrypt hashes.

## Deploy the sale catalog and administrator passkey changes

Before applying `202611040003`, pause POS sales, sync the existing queue, and record any pending transactions for reconciliation. Apply the reviewed migrations, then install the matching Android build and sync the catalog before resuming checkout. Each catalog revision is valid for 72 hours after issue or renewal and belongs to the registered POS device. A new upload with a missing, expired, or mismatched revision stays in the POS queue for reconciliation. The server still acknowledges an exact retry of a sale it already accepted. Upload queued sales before replacing a device or letting their revision expire.

After `202611040004`, set `ADMIN_WEBAUTHN_ORIGIN` to the dashboard's exact HTTPS origin and deploy the `admin-passkey` Edge Function before publishing the matching web build. For local WebAuthn testing, the verifier also supports `http://localhost:5173`. Use a stable hostname for each environment because the passkey is tied to that host. The function uses the server-side service-role credential and verifies the custom app session before issuing a challenge.

From the repository root in PowerShell, configure the function in the verified development project:

```powershell
$devRef = 'fhyqrgxwdqlyzxsnpthr'
$webOrigin = Read-Host 'Exact development dashboard origin'
.\node_modules\.bin\supabase.cmd secrets set --project-ref $devRef "ADMIN_WEBAUTHN_ORIGIN=$webOrigin"
.\node_modules\.bin\supabase.cmd functions deploy admin-passkey --project-ref $devRef --use-api
```

For each active System Administrator, a trusted database operator should issue a one-use enrollment code in the SQL Editor and deliver it privately. The administrator signs in to IMS, chooses **Use an enrollment code**, and follows the device prompt. Issue another code if they need to register a backup passkey. After each active administrator has verified a passkey on the intended origin, enable server enforcement. Management changes and management-only reads require verification in the last 15 minutes. Dashboard read access remains available after the management window expires.

```sql
select id, display_name from public.app_users
where role = 'system_admin' and is_active and deleted_at is null;

select public.issue_admin_passkey_enrollment('ADMIN_UUID'::uuid);
```

After enrollment and a successful passkey sign-in on the intended origin:

```sql
select public.enable_admin_passkey_policy();
```

For lost credentials, verify the administrator's identity through your recovery process, then run `public.revoke_admin_passkeys('ADMIN_UUID'::uuid)` and issue a fresh enrollment code as the database operator. Revocation removes the enrolled keys and invalidates all existing sessions for that administrator. Test recovery with a backup administrator before production rollout.

## Deploy POS device proof and credential storage

Apply reviewed migrations `202611040006` and `202611040007` before releasing the matching clients. Deploy the POS verifier to the same project as the database.

```powershell
.\node_modules\.bin\supabase.cmd functions deploy pos-gateway --project-ref fhyqrgxwdqlyzxsnpthr --no-verify-jwt --use-api
```

The Edge Function verifies a P-256 signature over the HTTP method, route and query, request body, session bearer hash, timestamp, and one-use nonce. It uses the service credential only to issue a short-lived database permit, then forwards the app operation with the public API role. The enrolled device keeps the private signing key in Android Keystore.

1. Verify sign-in and sign-out on the development build. Install the matching APK **over** the existing installation with the same application ID and signing identity. Room v17 removes the old session and prompts for password sign-in while preserving the stall, device-linked queue, and operating day.
2. For an already active handset, issue the enrollment code in the SQL Editor and deliver it privately. Paste it into the POS activation screen after signing in. It expires after 15 minutes. This uses the existing device ID and preserves its operating day. For a new or replacement handset, use the usual activation code from **Users & access**.

```sql
select id, stall_id, device_name
from public.devices
where is_active and deleted_at is null and hardware_id is not null;

select public.issue_pos_device_key_enrollment('ACTIVE_DEVICE_UUID'::uuid);
```

3. On the test handset, verify catalog sync, process restart, offline sale and reconnect, and online and offline sign-out. Confirm a saved synthetic bearer and copied Android ID alone cannot access the enrolled stall. Verify enrollment or re-enrollment after key loss and reconcile any previously queued sales.
4. An enrolled stall immediately requires device proof. After upgrading and testing every active handset, require proof across all stalls and revoke any remaining unbound Cashier sessions.

```sql
begin;
update private.pos_device_proof_policy set required = true where singleton;
update public.app_sessions as session_row
set revoked_at = now()
from public.app_users as app_user
where app_user.id = session_row.user_id and app_user.role = 'cashier'
  and session_row.pos_device_id is null and session_row.revoked_at is null;
commit;
```

## Migration verification

The rollback-only SQL fixtures in `tests/` cover sale/reversal behavior and the RBAC, stall assignment, device activation, operating-day, and deduction contracts. Run them only against a disposable database after applying every migration. The PGlite regression runner can exercise the activation and deduction fixtures locally with `node supabase/tests/pos_stock_receiving.integration.mjs --activation`; still validate the migrations on a development Supabase project before production.

Before production, rehearse account recovery with the actual staff, verify the
production migration history separately, and complete the release checks in
`docs/PROJECT_REVIEW.md`.

## Product pictures

Apply `202610140001_product_images.sql` before deploying the IMS image uploader.
It adds the optional product image URL, creates the public `product-images` bucket
(JPG, PNG and WebP, maximum 5 MB), restricts uploads to System Administrators, and
includes pictures in the device-bound POS catalog. Pictures are public catalog
assets; do not upload private documents. The IMS supports adding, replacing and
removing a picture through Add/Edit product. Sync the POS after saving; existing
installs keep their local data through the combined Room upgrade path to version 15. The POS caches viewed
pictures and shows a placeholder if a picture cannot be loaded.
