# Coolerz Ice Cream System Documentation

This folder contains the project architecture, data model, synchronization protocol, user guides, and operational commands.

## Run the IMS web dashboard locally

Open PowerShell in the project root:

```powershell
cd "C:\Desktop Apps and Files\Ice cream system"
pnpm install
pnpm dev
```

`pnpm dev` is the root shortcut for `pnpm --dir apps/web-ims dev`.
Both run the local development IMS against the development Supabase project;
neither deploys the website. See the [first-release deployment plan](FIRST_RELEASE_DEPLOYMENT_PLAN.md)
for the online IMS, Owner dashboard, and POS release workflow.

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

Open PowerShell in the Android project:

```powershell
cd "C:\Desktop Apps and Files\Ice cream system\apps\android-pos"
Copy-Item local.properties.example local.properties
```

Open `local.properties` in VS Code and update the Android SDK path plus the Supabase values. Use the Supabase **anon key** only; never use the service-role key in the mobile app.

## Build the POS development APK

```powershell
cd "C:\Desktop Apps and Files\Ice cream system\apps\android-pos"
.\gradlew.bat :app:assembleDevDebug
```

The development APK is created here:

```text
C:\Desktop Apps and Files\Ice cream system\apps\android-pos\app\build\outputs\apk\dev\debug\app-dev-debug.apk
```

## Build the POS production release APK

```powershell
cd "C:\Desktop Apps and Files\Ice cream system\apps\android-pos"
.\gradlew.bat :app:assembleProductionRelease
```

The current project does not yet have release signing configured, so this produces an **unsigned** release APK:

```text
app\build\outputs\apk\production\release\app-production-release-unsigned.apk
```

An unsigned APK cannot be installed as-is. Before distributing it to real devices, configure a signing key and release signing configuration, then rebuild this same variant.

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
