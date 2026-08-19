# Coolerz Ice Cream System Documentation

This folder contains the project architecture, data model, synchronization protocol, user guides, and operational commands.

## Run the IMS web dashboard locally

Open PowerShell in the project root:

```powershell
cd "C:\Users\Admin\OneDrive\RBB BSIT File Compilation\Ice cream system"
pnpm install
pnpm dev
```

Open the URL shown in the terminal, normally [http://localhost:5173](http://localhost:5173).

Before signing in, create `apps/web-ims/.env.local` from `apps/web-ims/.env.example` and enter the Supabase project URL and anon key.

Useful IMS commands:

```powershell
pnpm build
pnpm test
pnpm --dir apps/web-ims lint
```

## Configure the POS app locally

Open PowerShell in the Android project:

```powershell
cd "C:\Users\Admin\OneDrive\RBB BSIT File Compilation\Ice cream system\apps\android-pos"
Copy-Item local.properties.example local.properties
```

Open `local.properties` in VS Code and update the Android SDK path plus the Supabase values. Use the Supabase **anon key** only; never use the service-role key in the mobile app.

## Build the POS development APK

```powershell
cd "C:\Users\Admin\OneDrive\RBB BSIT File Compilation\Ice cream system\apps\android-pos"
.\gradlew.bat :app:assembleDevDebug
```

Because this project is inside OneDrive, generated Gradle output is redirected to Windows Temp to avoid OneDrive file-lock and reparse-point errors. The development APK is created here:

```text
%TEMP%\coolerz-pos-build\outputs\apk\dev\debug\app-dev-debug.apk
```

## Build the POS production release APK

```powershell
cd "C:\Users\Admin\OneDrive\RBB BSIT File Compilation\Ice cream system\apps\android-pos"
.\gradlew.bat :app:assembleProductionRelease
```

The current project does not yet have release signing configured, so this produces an **unsigned** release APK:

```text
%TEMP%\coolerz-pos-build\outputs\apk\production\release\app-production-release-unsigned.apk
```

It can be used for archive/testing purposes. Before distributing it to real devices, configure a signing key and release signing configuration, then rebuild this same variant.

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
cd "C:\Users\Admin\OneDrive\RBB BSIT File Compilation\Ice cream system\apps\android-pos"
.\gradlew.bat :app:installDevDebug
adb shell monkey -p com.icecreampost.pos.dev 1
```

## View POS logs and remove the dev app

```powershell
adb logcat -s CoolerzPOS
adb uninstall com.icecreampost.pos.dev
```
## Path/Directory of generated apk file
The APK is located here:
C:\Users\Admin\AppData\Local\Temp\coolerz-pos-build\outputs\apk\dev\debug\app-dev-debug.apk
You can open its folder with:
explorer "C:\Users\Admin\AppData\Local\Temp\coolerz-pos-build\outputs\apk\dev\debug"

## Apply the Android sync contract

Run this migration once in the Supabase SQL Editor after the existing migrations:

```text
supabase/migrations/202608140001_android_sync_contract.sql
```

It creates the idempotent `push_pos_transaction(jsonb)` RPC used by Android. The full endpoint, payload, header, cursor, retry, and recovery rules are documented in [ANDROID_SYNC_CONTRACT.md](ANDROID_SYNC_CONTRACT.md).
