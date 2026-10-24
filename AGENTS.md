# Database migration workflow

When work depends on whether a Supabase migration has been applied, check the linked project's remote migration history with `supabase migration list --linked` before claiming it is applied. Confirm `supabase/.temp/project-ref` is the intended environment; the development project is `fhyqrgxwdqlyzxsnpthr`. If CLI authentication or network access prevents the check, say the remote state is unverified. SQL run directly in the Supabase SQL Editor does not appear in CLI migration history.

For each new migration, use a unique version prefix in `supabase/migrations`, and give the user the exact repository-root PowerShell commands to inspect the migration list, preview with `supabase db push --dry-run`, apply with `supabase db push`, and verify with `supabase migration list --linked`. Never treat a dry run as an application. Review pending files and target project before applying. Do not use `db reset --linked` or run test fixtures against a live project.
