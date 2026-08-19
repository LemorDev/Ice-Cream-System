# Coolerz POS Android setup in VS Code

The Android foundation is already configured in this project. This guide covers the remaining local setup and USB deployment steps.

## 1. Configure Supabase locally

1. Copy `local.properties.example` to `local.properties` in this folder.
2. Keep the existing `sdk.dir` line if your Android SDK path is correct.
3. Replace the four Supabase placeholder values with the project URL and anon key.
4. Do not commit `local.properties`; it is already ignored by Git.

The Android app must use the Supabase anon key. Never put the service-role key in the Android project.

## 2. Create the Gradle wrapper if it is missing

This checkout does not currently contain `gradlew.bat`. If the `gradle` command is available, run this from `apps/android-pos`:

```powershell
gradle wrapper --gradle-version 8.10.2
```

After that, use `.\gradlew.bat` for all project builds. If `gradle` is not recognized, install Gradle or generate the wrapper once from another machine with Gradle installed.

## 3. Check the project

```powershell
.\gradlew.bat :app:assembleDevDebug
```

Because the project is inside OneDrive, generated Gradle output is redirected to Windows Temp to avoid OneDrive file-lock and reparse-point errors. Expected APK:

```text
%TEMP%\coolerz-pos-build\outputs\apk\dev\debug\app-dev-debug.apk
```

## 4. Connect the phone

On the Android phone:

1. Open Settings → About phone.
2. Tap Build number seven times.
3. Open Developer options.
4. Enable USB debugging.
5. Connect the phone by USB and accept the RSA debugging prompt.

In VS Code PowerShell:

```powershell
adb devices
```

The device status must be `device`, not `unauthorized` or `offline`.

## 5. Install and launch the development build

```powershell
.\gradlew.bat :app:installDevDebug
adb shell monkey -p com.icecreampost.pos.dev 1
```

The app is named `Coolerz POS` and the dev variant has the package suffix `.dev`.

## 6. Useful development commands

```powershell
.\gradlew.bat :app:assembleDevDebug
.\gradlew.bat :app:assembleProductionDebug
adb logcat -s CoolerzPOS
adb uninstall com.icecreampost.pos.dev
```

## What is already implemented

- Jetpack Compose UI foundation
- Room database, entities, DAOs, and schema generation
- WorkManager network-constrained periodic sync
- Retrofit, OkHttp, Kotlin serialization, and Supabase headers
- Kotlin Coroutines and Room Flow support
- Compose Navigation routes
- Hilt dependency injection
- App logging through `AppLogger`
- `dev` and `production` product flavors
- Offline-first package structure for data, domain-ready repositories, UI, and sync

The next feature work is the real checkout flow: cart state, local sale posting, inventory deduction, transaction reversal, and push/pull synchronization.
