# Coolerz Ice Cream System Documentation

This folder contains the project architecture, data model, synchronization protocol, user guides, and operational commands.

## Push updates from development to production

The release path is **feature branch → `develop` → `main`**. Review the source in GitHub and use its checks to validate the release. Cloudflare Pages publishes the web build, and Supabase migrations, Edge Functions, and Android APKs each have their own release step.

| Environment | Git branch | Web dashboard | Supabase project | Android app |
| --- | --- | --- | --- | --- |
| Development | `develop` | [Development IMS](https://ice-cream-system.pages.dev/) | `fhyqrgxwdqlyzxsnpthr` | DEV POS (`com.icecreampost.pos.dev`) |
| Production | `main` | [Production IMS](https://icecream-system.pages.dev/) | `rldsdzdlbxedjyqkvcpq` | Signed Coolerz POS (`com.icecreampost.pos`) |

Use test data in development and real stall records in production. Each Pages project uses its own branch, build mode, Supabase URL, and public key. [Environment setup](ENVIRONMENTS.md)

### Develop and test a change

1. Start new work from the latest `origin/develop`. Check your working tree first and preserve any unfinished work. From a clean checkout in repository-root PowerShell, use a short branch name for the change.

   ```powershell
   git status --short
   git fetch origin
   git switch -c codex/pos-login-ui origin/develop
   ```

2. Test locally with the development web app and DEV POS. For a production APK update, include the higher `versionCode` and new `versionName` in the reviewed change. For a database change, add a migration with a unique version prefix and run its fixtures on a disposable local database.
3. Stage the reviewed files, then inspect the staged diff, commit, and push. These commands use the login screen example from step 1.

   ```powershell
   git diff --cached --check
   git diff --cached
   git commit -m 'Update POS login screen'
   git push -u origin codex/pos-login-ui
   ```

   Open a GitHub PR with **base `develop`** and **compare your feature branch**. Wait for **Web checks**, **Database checks**, and **Android checks** to pass.
4. Coordinate any required development migrations and Edge Functions before the matching clients need them. Merge into `develop`, verify the development Pages deployment, and test the change with the hosted dashboard and DEV POS. Record the tested revision and any issues to fix before release. [Development acceptance checks](DEVELOPMENT_UAT.md)

### Promote the tested revision to production

1. Open a release PR with **base `main`** and **compare `develop`**. Review the complete release diff and wait for the three GitHub checks on the latest PR revision. Record which web, database, function, and APK updates this release needs.
2. For a backend change, schedule a quiet window and verify the production backup and recovery procedure. Sync the cashier's pending queue and keep the currently installed clients compatible during the rollout. Apply the reviewed production migration and deploy required Edge Functions before the new web build or APK uses them. If the old and new clients need incompatible contracts, plan a compatible transition before proceeding.
3. Merge the accepted PR into `main` using **Create a merge commit**. The production Pages project builds from `main` when automatic deployment is enabled. If deployment is manual, deploy the accepted `main` revision. Confirm the successful deployment shows the intended commit, `PRODUCTION DATABASE`, and the production Supabase endpoint.
4. If Android changed, build and sign the production APK from the accepted `main` revision. Share the versioned signed APK privately and install it over the current production app between shifts. Follow the [Android POS update procedure](#release-an-update-to-the-android-pos).
5. Verify the affected production workflow. For POS updates, check the app version, cashier sign-in, catalog and opening stock, then confirm the next controlled real transaction syncs once and leaves the queue clear. Record the `main` commit, deployed web revision, any applied migration versions, and the signed APK version and hash.

### Release steps by change type

| Change | How it reaches production |
| --- | --- |
| IMS or Owner dashboard UI | Promote through the PRs above. Cloudflare Pages builds `build:production` from `main`. |
| Android login screen or other POS UI | Promote the source and version bump, then build, sign, and distribute the new APK. Install it as an update with the same application ID and production signing key. |
| Database or RPC contract | Apply the reviewed migration to development first and test the complete workflow. During production release, verify the linked project and history with `migration list --linked`, inspect `db push --dry-run`, apply with `db push`, and confirm the remote history afterward. Restore the repository link to development when finished. [PowerShell migration commands](FIRST_RELEASE_DEPLOYMENT_PLAN.md#migration-commands-repository-root-powershell) |
| Supabase Edge Function | Deploy the reviewed function to development first, test it, then deploy it to the explicit production project reference during release. Verify the function is active and that administrator passkey origin or POS device proof behaves as expected. [Function deployment and enrollment](../supabase/README.md#deploy-the-sale-catalog-and-administrator-passkey-changes) |

## Run the IMS web dashboard locally

Open PowerShell in the project root:

```powershell
cd "C:\Desktop Apps and Files\Ice cream system"
pnpm install
pnpm dev
```

`pnpm dev` is the root shortcut for `pnpm --dir apps/web-ims dev`.
Both start the local IMS against the development Supabase project. Follow the
[release workflow](#push-updates-from-development-to-production) when the change is ready for hosting.

Open the URL shown in the terminal, normally [http://localhost:5173](http://localhost:5173).

Before signing in, configure the development URL and anon key in the ignored
`apps/web-ims/.env.development.local` file using
`apps/web-ims/.env.development.example` as the template. The development build
also requires the production URL for its environment-isolation check.

Owners can install the deployed dashboard on an iPhone from Safari using **Share → Add to Home Screen**. See [RBAC.md](RBAC.md) for roles, account setup, stall assignment, POS activation, and operating-day rules.

Useful IMS commands:

```powershell
pnpm build
pnpm test
pnpm --dir apps/web-ims lint
```

## Configure the POS app locally

Open PowerShell in the Android project. Create `local.properties` from the example only on a new checkout.

```powershell
cd "C:\Desktop Apps and Files\Ice cream system\apps\android-pos"
if (-not (Test-Path -LiteralPath 'local.properties')) {
    Copy-Item local.properties.example local.properties
}
```

Keep `sdk.dir` and your existing development configuration. For a production build, set `supabase.production.url` to `https://rldsdzdlbxedjyqkvcpq.supabase.co` and `supabase.production.anonKey` to that project's publishable key or legacy `anon` key. Keep service-role keys in server configuration. [Environment setup](ENVIRONMENTS.md)

## Build the POS development APK

```powershell
cd "C:\Desktop Apps and Files\Ice cream system\apps\android-pos"
.\gradlew.bat :app:assembleDevDebug
```

The development APK is created here:

```text
C:\Desktop Apps and Files\Ice cream system\apps\android-pos\app\build\outputs\apk\dev\debug\app-dev-debug.apk
```

## Release an update to the Android POS

Android POS changes, including login screen changes, ship in a new APK. Test the update in **DEV POS** first. Use the reviewed release revision for the production build.

Increase `versionCode` above the last distributed production build and set a new `versionName` in [app/build.gradle.kts](../apps/android-pos/app/build.gradle.kts). For example, an update from `0.1.0` with code `1` can use `0.1.1` with code `2`. Keep the production application ID `com.icecreampost.pos` and reuse the production signing key so Android can install the update over the existing app.

### Build the production APK

```powershell
cd "C:\Desktop Apps and Files\Ice cream system\apps\android-pos"
.\gradlew.bat :app:assembleProductionRelease --dependency-verification=strict
```

Gradle writes the unsigned APK to `app/build/outputs/apk/production/release/app-production-release-unsigned.apk`. Sign it with the existing `coolerz-production.p12` keystore before distribution.

### Sign and verify the APK

Run the following in PowerShell. Set `$releaseVersion` to the `versionName` you just built. Enter the keystore password when `apksigner` prompts for it.

```powershell
Set-Location 'C:\Desktop Apps and Files\Ice cream system'
$releaseVersion = '0.1.1'
$keystore = Join-Path $PWD 'apps/android-pos/.local/coolerz-production.p12'
$signer = Join-Path $env:LOCALAPPDATA 'Android/Sdk/build-tools/36.0.0/apksigner.bat'
$unsignedApk = Join-Path $PWD 'apps/android-pos/app/build/outputs/apk/production/release/app-production-release-unsigned.apk'
$signedDir = Join-Path $PWD "apps/android-pos/.local/releases/$releaseVersion"
$signedApk = Join-Path $signedDir 'app-production-release.apk'

if (-not (Test-Path -LiteralPath $keystore)) { throw 'Restore the original production keystore before signing' }
if (-not (Test-Path -LiteralPath $unsignedApk)) { throw 'Build the production APK first' }
if (Test-Path -LiteralPath $signedApk) { throw 'A signed APK already exists for this version' }
New-Item -ItemType Directory -Path $signedDir -Force | Out-Null

& $signer sign --ks $keystore --ks-type PKCS12 --ks-key-alias coolerz-production --out $signedApk $unsignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& $signer verify --verbose --print-certs $signedApk
if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }
Get-FileHash -LiteralPath $signedApk -Algorithm SHA256
```

The versioned `.local/releases` folder keeps the signed APK available after subsequent Gradle builds. Compare the signer certificate's SHA-256 digest against the previous production release. Retain the signed APK, its file hash, and the corresponding Git commit. Back up the keystore and store its password privately. [Android APK signing](https://developer.android.com/tools/apksigner)

### Distribute and install the update

Share the verified `app-production-release.apk` privately. Schedule the update between shifts and confirm the cashier's pending queue has synced. Open the APK on the phone and choose **Update**, or connect the cashier's phone with USB debugging and run the following in the same PowerShell session.

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
& $adb devices
& $adb install -r $signedApk
```

Installing over the existing production app preserves its local data and device activation. After installation, check the displayed version, cashier sign-in, catalog sync, and pending queue before the next shift. Use the [database setup guide](../supabase/README.md) when a release also changes the server contract.

## Connect an Android phone with USB debugging

On the phone:

1. Open **Settings → About phone**.
2. Tap **Build number** seven times to enable Developer options.
3. Go back to **Settings → Developer options**.
4. Enable **USB debugging**.
5. Connect the phone with a data-capable USB cable.
6. Accept the RSA fingerprint prompt on the phone.

In PowerShell, verify that the phone is connected:

```powershell
adb devices
```

The output must show your phone with the status `device`. If it shows `unauthorized`, unlock the phone and accept the USB debugging prompt.

If PowerShell cannot find `adb`, use the Android SDK platform-tools path for the current terminal:

```powershell
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
adb devices
```

## Install and run the POS development app on the phone

```powershell
cd "C:\Desktop Apps and Files\Ice cream system\apps\android-pos"
.\gradlew.bat :app:installDevDebug
adb shell am start -n com.icecreampost.pos.dev/com.icecreampost.pos.MainActivity
```

The explicit `am start` command launches the known main activity. Monkey is an optional alternative:

```powershell
adb shell monkey -p com.icecreampost.pos.dev 1
```

## View POS logs and remove the dev app

```powershell
adb logcat -s CoolerzPOS
adb uninstall com.icecreampost.pos.dev
```
Build output normally lives in `apps/android-pos/app/build`. Only checkouts whose path contains `OneDrive` redirect app build output to `%TEMP%\coolerz-pos-build`.

## Apply the Android sync contract

Apply every pending migration in filename order, following [the database setup guide](../supabase/README.md). The original Android endpoint was introduced by:

```text
supabase/migrations/202608140001_android_sync_contract.sql
```

It creates the idempotent `push_pos_transaction(jsonb)` RPC used by Android; later migrations include necessary corrections. The endpoint, payload, header, cursor, retry, and recovery rules are documented in [ANDROID_SYNC_CONTRACT.md](ANDROID_SYNC_CONTRACT.md). See [PROJECT_REVIEW.md](PROJECT_REVIEW.md) for remaining gaps between the intended contract and current behavior.

## Fingerprint login in the POS

1. Add a fingerprint in the phone's Android Settings, then sign in to the POS with
   the cashier's stall code, employee ID and password.
2. After password sign-in on an activated POS, tap **Enable fingerprint unlock**
   and authenticate with Android's prompt. The offer appears when this phone can
   use secure biometrics and the cashier has yet to enable them. Tap **Not now**
   to continue. Setup is also available under **More → Settings → Fingerprint login**.
3. Leaving the app or tapping **More → Lock POS** locks the retained session. Tap
   **Unlock with fingerprint** to return. A valid retained session can unlock offline.
4. Tap **Use password instead** to sign in online. This keeps the fingerprint setup
   when the same cashier signs back into a locked session with working enrollment.
   Signing out, switching cashiers, or recovering from an expired session clears setup.

Android verifies a Class 3 biometric (fingerprint, or another supported strong
biometric). The app stores no fingerprint data or saved password. An account marker
is encrypted with an authentication-required Android Keystore key and excluded
from backups. Changing enrolled biometrics invalidates that key; password sign-in
and re-enabling fingerprint login restores access. Background sale synchronization
continues independently of the screen lock.

To verify on a device, check successful unlock, cancellation, unrecognized fingers,
sensor lockout/unavailability, no enrolled fingerprint, app restart/backgrounding,
expired sessions, changed biometric enrollment, switching cashiers, and sign-out.
