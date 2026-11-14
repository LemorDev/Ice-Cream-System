# Development workspace

## Current safety status

Local development uses Supabase project `fhyqrgxwdqlyzxsnpthr`. The new production target is `rldsdzdlbxedjyqkvcpq`, created on 5 November 2026 for the real-sales release. The previous target `hdtizzdrbscchvqwcpkb` contains old test records, as confirmed by the user; it must not be used as the production migration target. Production credentials remain disconnected from development builds. Client keys belong only in ignored local files or the corresponding hosting environment.

The source labels the connected environment in IMS and POS. Set `VITE_SUPABASE_PRODUCTION_URL` and Android's `supabase.production.url` to the new production URL for the development isolation checks. This comparison does not need a production key. The development Cloudflare Pages project's comparison variable must also use the new URL; its actual Supabase URL/key remain development values. Configure real production client keys separately only when preparing that release.

## Current source version

On 2026-11-02, the workspace was recovered from `origin/codex/inventory-recipes-ui` at `e72814e` onto `codex/restore-latest-development`. The previous `develop` branch was 32 commits behind that version. Its uncommitted tracked edits were saved in the Git stash named `recovery-before-latest-development-2026-11-02`; untracked files were left in place. Do not blindly apply that old stash over the recovered source, because it contains edits based on the older UI.

Run `pnpm dev` from the repository root and open the localhost URL shown by Vite. Both `pnpm build` and `pnpm --dir apps/web-ims build:development` now build for the development database. Restart Vite after changing environment files.

## Create and configure the development project

The development project is created and configured locally. Its repository migrations were first applied on 2026-10-03; check `supabase migration list --linked` for any new pending migrations before testing. Use test-only stalls, users, products, and sales there. Never point both sets of configuration at one project.

For Android, preserve the existing `sdk.dir` line in `apps/android-pos/local.properties` and set the Supabase values using [local.properties.example](../apps/android-pos/local.properties.example). Only `supabase.dev.*` needs credentials. Keep `supabase.production.url` as the blocked endpoint comparison; leave its key unset. Build with `.\gradlew.bat :app:assembleDevDebug` from `apps/android-pos`. The development APK keeps its `.dev` application ID.

For IMS, `apps/web-ims/.env.development.local` contains the development URL/key plus the old production URL used only for the isolation check. `apps/web-ims/.env.local` also points to development. Run `pnpm --dir apps/web-ims dev` for development. Do not commit either `.local` file.

To prepare the development database, authenticate the Supabase CLI locally with `supabase login`, link explicitly to project `fhyqrgxwdqlyzxsnpthr`, review `supabase db push --dry-run`, and only then run `supabase db push`. Never link or push production as part of local development setup.

## Updating the POS without losing its local queue

Install an update over the existing app. Do not uninstall it to update: uninstalling deletes the Room database, including sales, deductions, stock movements, and operating-day changes that have not synced. The development package and production package are separate apps, and Android can keep both installed.

An in-place update requires the same application ID and signing certificate as the installed copy. Keep the development signing key consistent between dev updates. Before changing package identity or signing, sync the POS and confirm the pending queue is empty. If the phone is already uninstalled, use the IMS device-recovery flow; data that existed only on that phone cannot be reconstructed from Supabase.

### In-app APK updates

Set `pos.dev.updateManifestUrl` and `pos.production.updateManifestUrl` in the corresponding build's `apps/android-pos/local.properties`. Each value is the stable HTTPS URL for that package's release manifest. The phone needs HTTPS access to both the manifest and APK. Use a versioned APK URL and publish the manifest after the APK is available.

Build and install the first updater-enabled APK over the existing POS using its signing key. For each subsequent release, increment `versionCode` and sign with the same key. The development manifest uses the following fields.

```json
{
  "applicationId": "com.icecreampost.pos.dev",
  "versionCode": 6,
  "versionName": "0.1.5-dev",
  "apkUrl": "https://updates.example.invalid/dev/coolerz-pos-0.1.5-dev.apk",
  "sha256": "<64-character SHA-256 of the signed APK>"
}
```

Use `com.icecreampost.pos` for the production channel and the exact version and hash of its signed APK. Use `(Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()` to compute the hash. The POS checks for updates after activation and shows available releases in **More → App updates**. Select **Download and install** and confirm in Android. Android may first ask you to allow the POS as an installation source. [Android app update requirements](https://developer.android.com/google/play/app-updates)

## Reset workflow

Use the IMS reset actions only in the environment banner that says **DEVELOPMENT DATABASE**. Production resets are for an explicitly planned operational reset; they are not part of updating an APK. The production screen is labeled and requires the selected stall code in the confirmation phrase.

For a same-date UAT rerun, close the stall and confirm sync on the POS. In IMS, select **Data reset → Today’s operating day → Reset today’s operating day**. Back on the POS, select **More → Operating day → Sync operating day** and open the stall again. The reset archives the closing in the security audit log and keeps the sales and inventory ledger available.

### Backend setup for the UAT changes

Run these commands from the repository root to confirm the linked project and preview the pending migrations.

```powershell
if ((Get-Content -LiteralPath 'supabase/.temp/project-ref' -Raw).Trim() -ne 'fhyqrgxwdqlyzxsnpthr') {
    throw 'Link the development Supabase project before continuing.'
}
pnpm exec supabase migration list --linked
pnpm exec supabase db push --dry-run
```

Review all pending migration files, including `202611110001_pos_fingerprint_sign_in.sql` and `202611110002_reset_closed_operating_day.sql`. The following commands apply the reviewed set, verify the history, and deploy the matching POS gateway.

```powershell
pnpm exec supabase db push
pnpm exec supabase migration list --linked
pnpm exec supabase functions deploy pos-gateway --project-ref fhyqrgxwdqlyzxsnpthr
```

After updating the POS, sign in online with the cashier password once on each activated phone to set up offline password sign-in. On a new phone, complete activation and sync the product catalog before going offline.

Use **Open today's stall** to verify the cashier at the start of an operating day. An open day resumes when you return to the app. Use **More → Settings → Fingerprint login → Lock POS** to lock it manually. You can unlock with the saved cashier password or an enrolled fingerprint.

The POS queues records while offline. If you signed out and signed back in offline, use **More → Sync status → Reconnect sync** with the cashier password once the phone is online.
