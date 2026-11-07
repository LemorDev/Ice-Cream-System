# Public repository security review — 2026-10-05

This review covers the first-release source on `codex/inventory-recipes-ui`.
The PR must pass web, Android, and database checks before it is merged into
`develop` or `main`. No production accounts or sales are in use yet.

## Findings and fixes

| Area | Finding | Fix / verification |
| --- | --- | --- |
| JavaScript dependencies | Eight Dependabot alerts across `brace-expansion`, `js-yaml`, and `nanoid` in the prior lockfile. | Override vulnerable transitive ranges and update the lockfile. `pnpm audit --json` now reports zero advisories; PR checks rerun the audit. Alerts on the default branch remain open until the fix is merged there. |
| Password login | Anonymous web and POS password RPCs had no attempt limit. | Migration `202610050001_limit_password_login_attempts.sql` shares a private per-account counter, locks after five bad passwords in fifteen minutes, and returns no session during a fifteen-minute cooldown. The disposable database regression verifies both clients and no session issuance. Apply the migration in each Supabase environment before testing a public release. |
| Android data transfer | The POS manifest allowed OS backup of the Room database, which includes offline sales and the device binding. | Disable app backup and exclude app data from cloud backup and device transfer. The Android PR build validates the manifest. |
| Repository security settings | Dependency and secret scanning were disabled. | Enabled Dependabot alerts, secret scanning, and push protection on GitHub. Secret scanning reported zero open alerts on 2026-10-05. |

## Release follow-up

1. Apply all pending migrations to the **development** Supabase project and verify web and POS login there, including the cooldown. Follow `supabase/README.md`; never reset a linked database.
2. Define and test an administrator-assisted account recovery procedure before production use. A deliberate lockout can temporarily deny a legitimate user access.
3. Apply the reviewed migration to production before exposing the web and POS clients. Recheck Dependabot and secret-scanning alerts after merging to the production branch.
4. Preserve offline POS data during device replacement or incident recovery; disabling OS transfer means the documented POS recovery procedure is required.

The GitHub secret scanner and source review found no open secret alerts, but no
scanner can prove that all past content is harmless. Client publishable Supabase
keys and project URLs are public by design; database permissions must remain
effective even when those values are known.
