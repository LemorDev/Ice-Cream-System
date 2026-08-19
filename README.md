# Ice Cream POS System

Offline-first point-of-sale and inventory management system for an ice cream stall.

## Applications

- `apps/android-pos` — Kotlin and Jetpack Compose Android POS client. It will use Room as its local source of truth and WorkManager for synchronization.
- `apps/web-ims` — React, Vite, and Tailwind CSS web inventory management system.
- `supabase` — Reserved for database migrations, Row-Level Security policies, and Supabase configuration.
- `docs` — Project documentation and technical decisions.

## Prerequisites

- Android Studio with an Android SDK and JDK 17
- Node.js 20.19+ or 22.12+
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

> The Gradle wrapper is intentionally added after Android Studio/Gradle is available on the development machine.

## Planning

The implementation checklist and confirmed business rules are in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).
