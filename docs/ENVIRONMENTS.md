# Development and production environments

## Current safety status

The Android build and IMS now have separate development and production configurations. Development points to Supabase project `fhyqrgxwdqlyzxsnpthr`; production remains on its existing project. The development credentials are stored only in ignored local files and must not be committed.

The source labels the connected environment in IMS and POS. Development IMS refuses to start if its Supabase project matches production, and Gradle refuses to package or install a development APK while the Android project URLs match. Production reset confirmation also requires `RESET <stall code>`.

## Create and configure the development project

The development project is created and configured locally. Its repository migrations were first applied on 2026-10-03; check `supabase migration list --linked` for any new pending migrations before testing. Use test-only stalls, users, products, and sales there. Never point both sets of configuration at one project.

For Android, preserve the existing `sdk.dir` line in `apps/android-pos/local.properties` and set the Supabase values using [local.properties.example](../apps/android-pos/local.properties.example). The `supabase.dev.*` values must belong to the development project; `supabase.production.*` must belong to production. The development APK uses a `.dev` application ID, so it can sit beside the production app.

For IMS, `apps/web-ims/.env.development.local` contains the development URL/key plus the production URL used for the isolation check. Run `pnpm --dir apps/web-ims dev` for development. The normal production build uses the existing production environment configuration. Do not commit either `.local` file.

To prepare the development database, authenticate the Supabase CLI locally with `supabase login`, link explicitly to project `fhyqrgxwdqlyzxsnpthr`, review `supabase db push --dry-run`, and only then run `supabase db push`. Never link or push production as part of local development setup.

## Updating the POS without losing its local queue

Install an update over the existing app. Do not uninstall it to update: uninstalling deletes the Room database, including sales, deductions, stock movements, and operating-day changes that have not synced. The development package and production package are separate apps, and Android can keep both installed.

An in-place update requires the same application ID and signing certificate as the installed copy. Keep the development signing key consistent between dev updates. Before changing package identity or signing, sync the POS and confirm the pending queue is empty. If the phone is already uninstalled, use the IMS device-recovery flow; data that existed only on that phone cannot be reconstructed from Supabase.

## Reset workflow

Use the IMS reset actions only in the environment banner that says **DEVELOPMENT DATABASE**. Production resets are for an explicitly planned operational reset; they are not part of updating an APK. The production screen is labeled and requires the selected stall code in the confirmation phrase.
