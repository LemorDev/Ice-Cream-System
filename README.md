# Ice Cream POS System

Offline-first point-of-sale and inventory management system for an ice cream stall.

The current setup uses one active stall and three roles: System admin, Owner, and Cashier. Owners use the responsive web IMS, including an iPhone home-screen experience; Cashiers use the activated Android POS.

## Applications

- `apps/android-pos` — Kotlin and Jetpack Compose Android POS client, using Room locally and WorkManager for synchronization.
- `apps/web-ims` — React, Vite, and Tailwind CSS web inventory management system.
- `supabase` — Database migrations, Row-Level Security policies, and synchronization functions.
- `docs` — Project documentation and technical decisions.

## Prerequisites

- Android Studio with an Android SDK and JDK 17
- Node.js 22.12+ (the test command uses Node's TypeScript stripping)
- pnpm 9+

## Run the web application

```powershell
cd apps/web-ims
pnpm install
pnpm dev
```

Copy `.env.example` to `.env` and set Supabase values before connecting the dashboard to the backend.

## Run the Android application

Open `apps/android-pos` in Android Studio, allow Gradle to synchronize, then run the `app` configuration on an Android device or emulator.

The Gradle wrapper is included. From `apps/android-pos`, run `.\gradlew.bat :app:testDevDebugUnitTest :app:assembleDevDebug` to check the development build.

## Planning

The implementation checklist and confirmed business rules are in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

The latest code review, verified fixes, and remaining release blockers are in [docs/PROJECT_REVIEW.md](docs/PROJECT_REVIEW.md). The implementation checklist includes work that still needs acceptance testing.

The implemented access matrix, multi-stall Owner model, iPhone setup, and opening/closing workflow are in [docs/RBAC.md](docs/RBAC.md).
