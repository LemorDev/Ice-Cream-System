# Development workspace

## Current safety status

This workspace uses only Supabase development project `fhyqrgxwdqlyzxsnpthr`. Production project `hdtizzdrbscchvqwcpkb` is retained with its data, but its credentials are disconnected locally. Development credentials are stored only in ignored local files and must not be committed.

The source labels the connected environment in IMS and POS. The old production URL remains only as a safety comparison: development IMS and Android builds reject that endpoint. It is not a connection and does not need a production key. Production build support and example templates remain dormant for possible future use.

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

## Reset workflow

Use the IMS reset actions only in the environment banner that says **DEVELOPMENT DATABASE**. Production resets are for an explicitly planned operational reset; they are not part of updating an APK. The production screen is labeled and requires the selected stall code in the confirmation phrase.
