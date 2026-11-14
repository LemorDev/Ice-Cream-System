# New production database initialization

Verified and applied 5 November 2026 (Asia/Singapore).

## Target and scope

The user created and supplied new production project `rldsdzdlbxedjyqkvcpq` after choosing a separate real-sales environment. Development remains `fhyqrgxwdqlyzxsnpthr`. The old test production project `hdtizzdrbscchvqwcpkb` was not changed by this initialization.

Before application, verified the intended project reference, empty CLI migration history and empty public/private application schema, including absence of the `app_role` enum. Reviewed `db push --dry-run`: the expected 62 repository migrations, with unique versions and no seed or role changes. There was no existing application data to migrate or copy. No test fixture, reset, or migration-history repair was used.

## Application and verification

Applied all 62 existing migrations with pinned Supabase CLI 2.118.0. Then executed `migration list --linked` and confirmed every local version is recorded remotely through `202611040007`. A second `db push --dry-run` returned `upToDate: true` and an empty migrations list. This is an actual application followed by verification, not a dry-run-only result.

Read-only post-application checks confirmed zero stalls, users, products, transactions and devices; `is_system_admin()` without an app session returns false; the `anon` role cannot execute initial administrator bootstrap or passkey enrollment issuance; and the `product-images` bucket exists.

Restored and checked the root CLI link to development on completion. Production Edge Functions, initial account setup, production hosting, release signing and physical-device acceptance remain subsequent steps. No production client key or service credential was written to development configuration.

## Configuration changes

Updated the development web examples, production web example and Android example to the new production URL. Updated the ignored local web isolation comparison and Android production comparison URL while preserving development endpoints and keys. Checked those four endpoint/isolation conditions and `git diff --check` successfully. The development Cloudflare Pages project still needs its `VITE_SUPABASE_PRODUCTION_URL` comparison variable changed to `https://rldsdzdlbxedjyqkvcpq.supabase.co`; its actual URL/key must remain development values.

The production command in the release plan now targets the new reference. The earlier production audit remains historical evidence of the retired test target, not instructions to migrate it.

## Reference commands

These operations have already been completed for this initial schema. For later changes, review the pending files and target again before applying. Run from repository-root PowerShell; stop on any failed command.

```powershell
$env:SUPABASE_TELEMETRY_DISABLED = '1'
.\node_modules\.bin\supabase.cmd link --project-ref rldsdzdlbxedjyqkvcpq
Get-Content supabase/.temp/project-ref
.\node_modules\.bin\supabase.cmd migration list --linked
.\node_modules\.bin\supabase.cmd db push --dry-run
# Apply only after reviewing the exact pending SQL and target:
.\node_modules\.bin\supabase.cmd db push
.\node_modules\.bin\supabase.cmd migration list --linked
.\node_modules\.bin\supabase.cmd link --project-ref fhyqrgxwdqlyzxsnpthr
Get-Content supabase/.temp/project-ref
```

No new migration file was created. Future migrations require unique version prefixes and the same inspect/preview/apply/verify process. SQL Editor bootstrap calls are operational setup and do not add CLI migration history entries.

## Next steps

Create the initial production stall and administrator privately in this project's SQL Editor using `public.create_initial_system_admin`. Do not copy development sessions or passwords into a shared document. Then promote the reviewed release from `develop` to `main` with passing checks, prepare separate production Pages hosting and its exact passkey origin, deploy production Edge Functions, and complete administrator/device enrollment, a signed production APK, real opening stock, backup/recovery and combined acceptance before real sales.
