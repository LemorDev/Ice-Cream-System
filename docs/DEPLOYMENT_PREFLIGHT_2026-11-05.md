# Deployment stage 1: local preflight

Date: 5 November 2026 (Asia/Singapore).

## Candidate and scope

Checked the working tree on `codex/restore-latest-development`, based on `e72814e`. There were 75 modified tracked files before this session's edits, plus untracked security functions, migrations, tests and other files. Local `develop` is still `c90835f`; local `main` is also `c90835f`. A read-only `git ls-remote --heads origin` verified remote `develop` at `c90835f`, remote `main` at `3a3e4f3`, and remote `codex/inventory-recipes-ui` at `e72814e`. The tested candidate is the working tree, not the old branch commits. This check did not inspect branch protection settings or remote CI.

Existing application edits were retained. This session adds three missing Android dependency-verification metadata entries, a parent-scoped minimatch override and its lockfile update, and deployment documentation. No commit, branch switch, remote push, migration application, function deployment, web publishing or phone installation was performed. Candidate review/commit and remote required-check results remain outstanding before publishing.

The current code already has `apps/web-ims` script `build:production` and a production-mode build in `.github/workflows/pr-checks.yml`; these were present before this session. Default `build` still deliberately targets development. The CI toolchain pins Node 24.19.0 and pnpm 11.21.0. Local verification used Node 24.19.0 and the installed pnpm command reporting 12.15.0; a remote CI pass with its pinned toolchain remains required.

## Verification

| Check | Result |
| --- | --- |
| Web lint | Passed |
| Web tests | 54 passed, zero failures |
| Development web build | Passed with isolated CI endpoint/key placeholders |
| Production web build | Passed with isolated CI endpoint/key placeholders |
| Production asset environment | Production placeholder present; development placeholder absent |
| Disposable database regressions | Receipt integration and all 17 extended/activation SQL fixtures passed |
| POS gateway signatures | Real P-256 signatures, replay rejection, signed payload/query and relay-route checks passed |
| Room upgrade harness | Versions 3, 4, 11-16 and cloud v4 to v17 preserved pending operational data and removed the legacy session; secure-delete/WAL check passed |
| Android unit tests, lint, dev APK and production release assembly | Passed together with strict dependency verification; 125 tests in 22 suites, zero failures/errors/skips; Gradle BUILD SUCCESSFUL in 7m 20s |
| Dependency advisory audit | Passed after scoped minimatch fix: no known vulnerabilities; moderate-severity CI gate retained |
| CI-pinned package manager | pnpm 11.21.0 frozen-lockfile installation passed with `--ignore-scripts`; resolution skipped |
| Live backend and handset acceptance | Not exercised in this local preflight |

Web builds reported the existing large-chunk warning (approximately 953 KB JavaScript, 273 KB gzipped). This did not fail either build. The builds use placeholder public keys and cannot establish hosted integration correctness. Live database migration status remains unverified in this stage; check it using the linked-project workflow in stage 2.

Android lint reported 57 warnings and no errors. Gradle also warned that AGP 8.8.2 was tested through compileSdk 35 while this project compiles against 36. The production APK produced here is `app-production-release-unsigned.apk`, SHA-256 `ECCF45F80BC223429417E8D1FA762018F10ED79638B85F3E9E88DC2F990E6820`. Assembly is build evidence only; release signing, production configuration and handset acceptance remain required.

The audit initially reported [GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249) for `minimatch@10.2.6`, reached through `@typescript-eslint/typescript-estree@8.65.0`. A delegated remediation added exact override `'@typescript-eslint/typescript-estree>minimatch': 11.0.1` in `pnpm-workspace.yaml` and updated the lockfile. Upstream typescript-estree still requests minimatch 10, so this temporary parent-scoped override uses the patched major only for the affected dependency; ESLint's minimatch 3 remains unchanged. Afterward, the audit reported no known vulnerabilities, web lint and all 54 tests passed, and a frozen installation using CI's pinned pnpm 11.21.0 passed. Revisit the override when upstream accepts the patched major. Remote CI is still required.

## Android dependency verification repair

The initial strict build stopped because three artifact checksums were absent. These were missing metadata entries, not mismatches against existing expected hashes. Each cached artifact's SHA-256 was compared with the publisher repository's checksum over HTTPS before adding its exact entry. Strict verification remains enabled.

| Artifact | Verified SHA-256 |
| --- | --- |
| `org.junit:junit-bom:5.9.2`, `.module` | `ab137ba5a8e32c9b066bf9126a1c76dd5614b724ba5c0b02549772b5e9f4cf1f` |
| `org.junit:junit-bom:5.9.3`, `.module` | `b401fd25901e582a524aa5343c4b39e28bc56e24961c1069bf2b4bbfcee46b93` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0`, `.pom` | `1239e9dbe1397cd5971342956b2511bc3ace7b641842e4372a088dcfa8b9ad55` |

Checksum sources: [JUnit 5.9.2](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.9.2/junit-bom-5.9.2.module.sha256), [JUnit 5.9.3](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.9.3/junit-bom-5.9.3.module.sha256), [Coroutines 1.8.0](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-bom/1.8.0/kotlinx-coroutines-bom-1.8.0.pom.sha256).

## Backend rollout additions

The newer security source requires both `admin-passkey` and `pos-gateway` Edge Functions, custom-session checks, server-side secrets, passkey enrollment and POS device-key enrollment. Follow [supabase/README.md](../supabase/README.md) with the actual reviewed migration list. Use a stable exact HTTPS origin for `ADMIN_WEBAUTHN_ORIGIN` before enrolling passkeys. Database migration application alone does not deploy these functions.

An independent read-only agent review confirmed that the web passkey gate applies to fresh admin sessions immediately, and Android immediately routes login and normal calls through the gateway. Deploy/configure the functions and prepare private admin enrollment before those clients are tested. Existing-device enrollment codes expire after 15 minutes; enrolling a device immediately requires proof for that stall. Enroll devices before enabling global proof policy. Drain/reconcile old queues before the 72-hour catalog-revision rollout and check automatic handset date/time for the gateway's 90-second tolerance.

The security assessment dated 4 November describes its assessed revision. Passing local remediation fixtures does not establish the deployed schema, close every finding or replace hosted negative tests and actual handset acceptance. Retain those release gates.

Next: complete candidate review/commit and pinned CI verification, then verify the development project's remote migration history and review pending SQL before applying anything. Keep production credentials disconnected during development preparation.
