# Coolerz IMS and POS security assessment

**Assessment date** 4 November 2026 (Asia/Singapore)  
**Revision** Working tree on `codex/restore-latest-development` at base commit `e72814e`, including the local SQL and application changes present during review.  
**Release disposition** Block release of the assessed revision until SEC-01 is fixed and independently retested.

## 1. Executive summary

The database authorization layer has a critical fail-open condition. When no valid application session exists, two admin authorization helpers can return SQL `NULL`. Several privileged functions grant execute access to the PostgREST `anon` role and use guards that fall through on `NULL`. The reviewed code exposes paths for creating stalls, managing staff, assigning Owner access, and resetting sales or stock. A disposable database confirmed the predicate results and effective grants without executing those privileged operations. Exposure and exploitation in any hosted environment remain unverified. See [SEC-01](#sec-01--critical--privileged-rpcs-fail-open-without-a-session).

An independent high-risk integrity issue affects POS sales. A caller with a valid Cashier session and active-device credentials can submit internally consistent sale prices and ingredient costs that are never compared with a server-authorized catalog snapshot. The risk is manipulation of financial reporting and stock deductions through the normal sync boundary. See [SEC-02](#sec-02--high--the-pos-client-controls-financial-and-inventory-snapshots).

The review recorded **11 confirmed findings** and **4 potential findings**. All 20 OWASP categories received a scoped review. Five still require additional evidence before a pass or fail decision. The percentage measures review coverage only.

## 2. Application architecture

| Surface | Verified implementation and trust boundary |
| --- | --- |
| Web IMS | React 19 and TypeScript single-page application built with Vite 6. Supabase JS makes browser-to-PostgREST and Storage requests. UI routes separate System Administrator and Owner views. iPhone access is through the web app, which has a web manifest and a production service worker that claims clients without implementing a fetch cache. [Package manifest](../apps/web-ims/package.json) · [API client](../apps/web-ims/src/lib/api.ts) · [Service worker](../apps/web-ims/public/service-worker.js) |
| Android POS | Native Kotlin and Jetpack Compose with Hilt, Retrofit, OkHttp, kotlinx.serialization, Room, WorkManager and Coil. Minimum SDK 26 and target SDK 36. Room stores the session, device binding, sales, inventory and offline queue. Android Keystore AES-GCM protects the optional fingerprint account marker. [Build configuration](../apps/android-pos/app/build.gradle.kts) · [Database](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/local/database/CoolerzDatabase.kt) · [Fingerprint store](../apps/android-pos/app/src/main/java/com/icecreampost/pos/security/FingerprintLoginStore.kt) |
| Backend | Supabase PostgreSQL, PostgREST functions, RLS and Supabase Storage. Both clients send a public API key and an application bearer in `X-Session-Token`. POS also sends `X-Device-Id`. Database functions resolve the custom bearer to an active account and role. The custom login RPCs use pgcrypto and 12-hour sessions. Account and network throttling and the stronger password policy are present in the local migration set. [Session lookup](../supabase/migrations/202608040001_initial_schema.sql#L151) · [Local login hardening](../supabase/migrations/202611040001_harden_password_sign_in.sql) |
| APIs and data | `/rest/v1/rpc/` covers login, staff, stalls, activation and recovery, financial reporting, catalog, sales, inventory, and day closing. Selected REST tables use RLS. The `product-images` Storage bucket is intentionally public and configured for JPEG, PNG and WebP with a 5 MiB object limit. Restricted data includes credentials, sessions, staff identities, device identifiers, sales, costs and inventory. [Web API](../apps/web-ims/src/lib/api.ts) · [POS API](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/remote/SupabaseApi.kt) · [Storage policies](../supabase/migrations/202610140001_product_images.sql#L6) |
| Delivery | GitHub Actions runs PR checks. Repository documentation proposes Cloudflare Pages for the web and a signed APK for POS. The proposal supplies context for release checks. Current hosted settings and the distributed production binary were unavailable for inspection. [PR workflow](../.github/workflows/pr-checks.yml) · [Deployment plan](FIRST_RELEASE_DEPLOYMENT_PLAN.md) |

## 3. Scope and rules of engagement

In scope were the checked-out source, SQL migrations and grants, local configuration templates, dependency metadata, CI workflow, and the available development APK. Review covered source input and output validation, session and device identity, data boundaries, cryptography, privacy, logging, and release configuration. No native iOS project was identified in the reviewed repository. Native iOS controls are outside scope. The Safari home-screen installation falls under web coverage.

Validation used local source, generated build outputs, public advisory services, and a fresh in-memory database with synthetic records. Hosted API and TLS probes, privileged exploit execution, device extraction, intrusive payloads, access to other users' records, and production mutations were excluded. The linked Supabase project reference matched the documented development project. `supabase migration list --linked` returned `AccessTokenRequiredError`, leaving hosted schema, grants, and deployment status unverified. An authenticated history check also cannot account for SQL applied directly through the SQL Editor. [Repository migration workflow](../AGENTS.md)

## 4. Methodology and validation evidence

| Check | Result and limitation |
| --- | --- |
| SQL/RLS review | Loaded 56 migrations into disposable PGlite with real pgcrypto and a minimal Storage shim. Inspected catalog grants and RLS metadata under `anon`, and evaluated the auth predicates against a synthetic stall. `current_app_user_id()` was `NULL`, both admin checks were `NULL`, and the simulated `IF NOT` guard fell through. The catalog showed 55 public security-definer functions callable by `anon`, including eight selected privileged endpoints reported in SEC-01. This is local SQL evidence, separate from hosted gateway behavior. |
| Local regression tests | The disposable database suite passed its receipt, session throttling, activation, reset, reconciliation, and related fixtures. The 54 web tests passed. These suites did not cover the unauthenticated privilege path. The latest local Android DevDebug verification in this workspace recorded 123 passing unit tests across 21 suites, including the 429 contract test. Android results were reused from the preceding verification, and the APK was independently inspected during this assessment. |
| Dependency and CI review | `pnpm audit --json` returned one moderate npm advisory in development-only `minimatch` paths. `pnpm audit --prod --json` returned zero advisories. Resolved `productionReleaseRuntimeClasspath` offline, parsed 152 Maven coordinate/version pairs with zero failed dependency entries, and received zero advisory matches from the OSV batch query. The Maven query covered the resolved release runtime graph. Build plugins, SDKs, Gradle itself, and non-Maven inputs were outside that advisory query. [OSV batch query documentation](https://google.github.io/osv.dev/post-v1-querybatch/) |
| Android artifact | `aapt dump badging`, `aapt dump xmltree`, and `apksigner verify --print-certs` confirmed `com.icecreampost.pos.dev`, SDK 26–36 configuration, expected debug status and Android Debug signing for the development artifact. The merged manifest included the app launcher and debug Compose preview, permission-protected WorkManager and profile installer components, and backup disabled. No application deep-link handler or WebView use was found in source. Release manifest, signing identity, and on-device behavior require separate checks. |
| Secrets and infrastructure | Searched 281 scoped current source and configuration text files for common private-key, service-role, token and credential-bearing connection string patterns. No candidate matches were returned. The scan emitted only metadata and did not cover Git history, ignored local secrets, binary content or hosting credentials. The public API keys are client configuration by design. Production response headers, certificates, API CORS, Storage configuration, and hosting access controls were unavailable. |

**Classification.** Confirmed means supported by source, catalog, artifact, or advisory evidence for the assessed revision. Potential means the evidence identifies a plausible weakness whose exposure depends on runtime configuration, data, or device behavior. Informational observations describe verified controls or scope. Severity reflects potential business impact and prerequisites. Confidence reflects the quality of evidence for the stated scope.

## 5. OWASP Web Top 10:2025 results and compliance checklist

Category references use the [official OWASP Top 10:2025](https://top10.owasp.org/2025/).

| OWASP ID | Security Category | Status | Evidence | Risk | Recommendation |
| --- | --- | --- | --- | --- | --- |
| A01:2025 | Broken Access Control | FAIL | SEC-01, effective grants and nullable admin checks verified in disposable SQL | Critical | Deny unless authorization is explicitly true. Review all exposed privileged RPCs and add role-matrix tests. |
| A02:2025 | Security Misconfiguration | NEEDS REVIEW | SEC-11, no repository-managed hosting headers found and deployed settings unavailable | Medium, potential | Verify headers, CORS, Storage policy, host isolation and HTTPS on the approved deployment. |
| A03:2025 | Software Supply Chain Failures | FAIL | SEC-08 and SEC-09, mutable CI action tags and affected npm tooling versions | Medium | Pin build inputs, retain dependency audits, and upgrade affected tooling. |
| A04:2025 | Cryptographic Failures | NEEDS REVIEW | SEC-13, versioned hashes and random session tokens reviewed. Legacy hash inventory and hosted TLS remain unknown. | Low for legacy hash issue, transport unverified | Inventory hash formats and costs without exporting hashes. Verify hosted TLS and retire weak formats. |
| A05:2025 | Injection | FAIL | SEC-05, database receipt field reaches CSV output with quoting alone. Sampled SQL uses bound RPC arguments. | Medium | Export receipt fields as literal text and enforce the documented receipt contract. |
| A06:2025 | Insecure Design | FAIL | SEC-02 and SEC-06, POS-supplied financial values and password-only administrator authority | High | Establish authoritative catalog revisions and step-up authentication. |
| A07:2025 | Authentication Failures | FAIL | SEC-03 and SEC-06, client-only logout and privileged single-factor sign-in | Medium | Revoke the current session server-side and require administrator MFA. |
| A08:2025 | Software or Data Integrity Failures | FAIL | SEC-02 and SEC-08, untrusted sale snapshots and weak build-input provenance | High | Validate sale data against immutable server revisions and verify release inputs. |
| A09:2025 | Security Logging and Alerting Failures | FAIL | SEC-07, login functions keep throttle state without durable authentication events | Medium | Record sanitized events and prove alert delivery and retention. |
| A10:2025 | Mishandling of Exceptional Conditions | FAIL | SEC-01, missing, invalid and expired sessions can produce a nullable guard | Critical | Deny on missing identity and test negative paths and transaction rollback. |

## 6. OWASP Mobile Top 10:2024 results and compliance checklist

Category references use the [official OWASP Mobile Top 10:2024](https://owasp.github.io/www-project-mobile-top-10/2023-risks/). The scope is native Android and its backend trust boundaries.

| OWASP ID | Security Category | Status | Evidence | Risk | Recommendation |
| --- | --- | --- | --- | --- | --- |
| M1:2024 | Improper Credential Usage | FAIL | SEC-04, reusable session bearer written to Room as text | Medium | Store credentials separately under Android Keystore protection. |
| M2:2024 | Inadequate Supply Chain Security | FAIL | SEC-08, CI references and Gradle artifacts lack repository-enforced immutable verification. No match in the scoped Maven query. | Medium | Pin actions, verify Gradle downloads, and scan runtime and build dependencies. |
| M3:2024 | Insecure Authentication/Authorization | FAIL | SEC-01, SEC-03 and SEC-15, shared fail-open RPCs, client-only logout and replayable device identifier | Critical | Fix server guards, revoke sessions, and require proof of device-key possession. |
| M4:2024 | Insufficient Input/Output Validation | FAIL | SEC-02, transaction JSON validates arithmetic without checking catalog-derived values | High | Recompute or verify prices, quantities and recipe components against approved revisions. |
| M5:2024 | Insecure Communication | NEEDS REVIEW | SEC-12, custom session header may survive a cross-origin redirect. No permissive trust manager found. | Medium, potential | Bind authenticated requests to the approved HTTPS origin and test redirect and certificate failures. |
| M6:2024 | Inadequate Privacy Controls | NEEDS REVIEW | SEC-14, persistent device identifier and local history. Backup exclusions verified in source. | Low, potential | Confirm data lifecycle, device retirement, logs and cache policy. |
| M7:2024 | Insufficient Binary Protections | FAIL | SEC-10, no enforced release signing or R8 configuration in the reviewed build. DevDebug signature verified. | Medium | Build and attest a signed, non-debuggable release and exercise R8-protected flows. |
| M8:2024 | Security Misconfiguration | FAIL | SEC-10, a production-flavor debug build is documented and production-specific checks are absent from CI | Medium | Forbid debug builds against production and validate the final manifest and signing identity in CI. |
| M9:2024 | Insecure Data Storage | FAIL | SEC-04, ordinary SQLite stores session bearer and offline records. Backup is disabled. | Medium | Remove plaintext bearer from Room and verify the migration against SQLite and WAL files. |
| M10:2024 | Insufficient Cryptography | NEEDS REVIEW | Reviewed AES-GCM fingerprint marker and Keystore configuration. Hardware backing, key invalidation and migration behavior need a handset. See SEC-04 and SEC-13 for adjacent storage and legacy-hash risks. | Runtime assurance gap | Verify nonce handling, key lifecycle and recovery on supported devices. |

**Coverage calculation.** Web 10/10 reviewed and Mobile 10/10 reviewed, for **20 ÷ 20 × 100 = 100%** category coverage. The review produced 15 FAIL and 5 NEEDS REVIEW decisions. There are 0 PASS, 0 NOT APPLICABLE and 0 NOT TESTED category rows. The five NEEDS REVIEW decisions and the exclusions above limit conclusions about live operation. Coverage is not a security score or compliance certification.

## 7. Vulnerability findings

### SEC-01 · Critical · Privileged RPCs fail open without a session

**Remediation status** Fixed and tested locally. The forward migrations make the shared authorization predicates deny missing, invalid, expired, revoked and malformed identity. The disposable role matrix checks privileged RPC denials, direct table policy enforcement, and valid administrator access through the normal gateway role. The reset fixture forces an audit insert to fail and verifies that stock and operating-history resets roll back before a successful retry. Hosted migration history and gateway retesting require verification. [Authorization migration](../supabase/migrations/202611040002_deny_missing_app_authorization.sql) · [Request identity migration](../supabase/migrations/202611040005_deny_malformed_request_identity.sql) · [Authorization fixture](../supabase/tests/app_authorization.sql) · [Reset fixture](../supabase/tests/system_admin_data_reset.sql)

**Classification** Confirmed in the assessed SQL and local catalog. **Confidence** High. **OWASP** A01, A10, M3.

**Evidence.** A missing, revoked or expired session leaves `current_app_role()` without a result. `is_system_admin()` compares that result using `=`. `can_manage_stall()` combines the same comparison with the existence of an active stall. An anonymous request for a known active stall therefore gets `NULL`. PL/pgSQL does not enter `IF NOT predicate THEN ...` when the result is `NULL`. The catalog confirmed `SECURITY DEFINER` and `anon` execute access for `create_managed_stall`, `save_managed_user`, `set_owner_stalls`, `get_pos_device_status`, `create_device_activation`, `authorize_pos_recovery`, `reset_stall_transactions` and `reset_stall_stock`. The audit actor field accepts `NULL`, so it does not enforce identity at write time. Bootstrap and promotion functions were excluded from this finding because `anon` lacked execute access in the inspected catalog. [Session lookup](../supabase/migrations/202608040001_initial_schema.sql#L151) · [Admin predicate and staff/stall RPCs](../supabase/migrations/202609080003_rbac_business_days.sql#L51) · [Stall predicate](../supabase/migrations/202609090002_owner_monitoring_only.sql#L31) · [Staff save](../supabase/migrations/202611040001_harden_password_sign_in.sql#L210) · [POS management](../supabase/migrations/202610010002_pos_activation_and_deduction_alignment.sql#L100) · [Sales reset](../supabase/migrations/202609290004_reset_financial_reporting.sql#L20) · [Stock reset](../supabase/migrations/202609130001_system_admin_data_reset.sql#L54) · [OWASP Broken Access Control](https://top10.owasp.org/2025/A01_2025-Broken_Access_Control/) · [PostgreSQL comparison semantics](https://www.postgresql.org/docs/17/functions-comparison.html)

**Business impact.** A reachable affected API could let a caller without an application session create a stall, alter staff or Owner access, or disrupt device operations. Many paths require a valid target stall or record identifier. The sales reset deletes operating-day data and archives transactions, and the stock reset alters inventory totals. A caller who can establish Owner access could subsequently read financial information. Hosted exposure and any past use remain unverified.

**Remediation and implementation.** Centralize explicit authentication and two-valued authorization. Make role predicates return `false` for a missing identity and require `IS TRUE` at every privileged guard. Inventory all exposed routines, including `<>` role checks and nullable flags. Gate staff, device and reset operations on a valid app session at entry. A public gateway role alone is not an app identity. Reduce unnecessary direct grants and keep internal helpers private. For any hosted deployment found to contain this code, restrict affected RPCs while preparing the fix and preserve audit evidence. Section 9 shows the guard pattern.

**Retest.** In a disposable database and then through the approved staging gateway, verify that omitted, invalid, expired, revoked and disabled-account tokens are denied before any read or mutation. Include PostgREST `anon` and `authenticated` callers without a valid app session, as well as Owner, Cashier, authorized Administrator, wrong stall, invalid resource and direct-table cases. Compare fixture state before and after denied writes. Confirm valid administrator operations and intentional public login continue to work.

### SEC-02 · High · The POS client controls financial and inventory snapshots

**Remediation status** The POS saves a device-bound catalog revision with each sale. The database validates new uploads against the revision. Disposable tests reject foreign, expired and tampered revisions or sale values without leaving sales, line items, upload records or stock changes. An exact retry of a previously accepted sale stays idempotent after the revision expires. Verify hosted migration history and reconcile any older queued sales during rollout. [Sale catalog migration](../supabase/migrations/202611040003_authorize_pos_sale_catalog.sql) · [Sale regression fixture](../supabase/tests/r08_development_contract.sql)

**Classification** Confirmed in source. **Confidence** High. **OWASP** A06, A08, M4.

**Evidence.** The active `push_pos_transaction_v2` wrapper calls `push_pos_transaction_v2_unchecked`. The latter checks role, active device, operating-day identity, replay, product ownership and arithmetic. `unit_price` comes from the client and is checked for nonnegativity and consistency with `line_total`. The client supplies the components and their costs. The server checks stock for submitted components and sums their supplied costs. It permits an empty component array even for a product whose recipe would require inventory. There is no server-authorized price or recipe revision against which the submission is compared. The closed-day wrapper also uses accepted amounts to recalculate totals. [POS sale validation and writes](../supabase/migrations/202611030001_deduct_powder_on_open.sql#L102) · [Closing wrapper](../supabase/migrations/202611030001_deduct_powder_on_open.sql#L346) · [Earlier snapshot migration](../supabase/migrations/202610060004_sale_day_cost_snapshots.sql#L64)

**Business impact.** A caller with a valid Cashier session and accepted device credentials could submit sales with incorrect prices, costs or ingredient deductions for that stall. The results could affect cash reconciliation, purchasing, reported profit and previously closed days. The review traced the data flow and did not submit a falsified sale.

**Remediation and implementation.** Retain immutable server-issued catalog revisions or signed offline price and recipe records. Include an approved revision identifier in each sale and derive price, line totals, expected components and cost from the corresponding revision on the server. Represent legitimate discounts as authorized events. Quarantine unknown or unsupported revisions for controlled reconciliation while retaining queued work. Section 9 describes the validation order.

**Retest.** Upload a legitimate old-catalog sale after prices or recipes change. Reject mismatched prices, missing required components, changed costs, unrecognized revisions and incorrect device/stall bindings without partial writes. Replay a valid payload and confirm exactly one financial and inventory effect. Verify late-sale correction uses validated amounts.

### SEC-03 · Medium · Sign-out leaves the issued bearer valid on the server

**Remediation status** Both clients call a self-revocation RPC that revokes only the supplied bearer. The POS queues offline revocation under Keystore protection and retries when connected. The web clears local access and makes a bounded in-memory retry. Disposable tests verified revocation and isolation from another valid session. Hosted sign-out tests require verification. [Revocation migration](../supabase/migrations/202611040006_revoke_app_session_on_sign_out.sql) · [Database fixture](../supabase/tests/pos_device_proof_and_logout.sql)

**Classification** Confirmed in source. **Confidence** High. **OWASP** A07, M3.

**Evidence.** Web `signOut()` removes `sessionStorage`. Android sign-out clears the token store and Room session. Neither calls a server revocation endpoint. The server accepts a matching hash until the fixed expiry, explicit revocation or account disablement. A separate trigger revokes sessions for selected user changes. Browser JavaScript on the same origin can read the web bearer from `sessionStorage`. [Web storage and sign-out](../apps/web-ims/src/lib/session.ts#L62) · [Android sign-out](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/SessionRepository.kt#L164) · [Server validity checks](../supabase/migrations/202608040001_initial_schema.sql#L152) · [Revocation trigger](../supabase/migrations/202610060002_revoke_sessions_on_user_change.sql#L3) · [OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)

**Business impact.** A previously copied bearer can remain usable after sign-out for the remaining session lifetime. Administrator sessions carry broad access. Exploitation requires prior acquisition of a bearer.

**Remediation and implementation.** Add a self-revocation RPC that hashes the presented token and revokes only the matching session without accepting a target user ID. Make online sign-out revoke before reporting server completion, and define offline sign-out behavior and a bounded revocation retry. For web, consider a same-origin backend with an `HttpOnly`, `Secure`, `SameSite` session cookie and corresponding CSRF controls. Use a shorter elevated lifetime for admin operations.

**Retest.** Sign out from both clients in staging and confirm a pre-logout test token fails immediately on a read-only protected endpoint. Verify a second valid session survives single-session revocation and offline sign-out preserves unsynced sales while respecting the documented expiry and revocation behavior.

### SEC-04 · Medium · POS persists a reusable bearer in plaintext Room storage

**Remediation status** Room v17 removes the legacy session and preserves the stall binding and queued business data. The POS stores new bearers as AES-GCM ciphertext in a backup-excluded file under Android Keystore protection. The synthetic disk fixture verified that secure deletion and the WAL checkpoint removed its legacy bearer. Physical handset upgrade and Keystore lifecycle tests require verification. [Credential vault](../apps/android-pos/app/src/main/java/com/icecreampost/pos/security/PosCredentialVault.kt) · [Room upgrade test](../apps/android-pos/scripts/verify-room-upgrade.mjs)

**Classification** Confirmed in source. **Confidence** High. **OWASP** M1, M9.

**Evidence.** `AppSessionEntity.sessionToken` is a `String` written by `SessionRepository` into ordinary Room SQLite. The database builder shows no application-level encryption layer. Android Keystore protects the separate fingerprint account marker. Backup is disabled and both cloud backup and device transfer exclude app files. [Session entity](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/local/entity/AppSessionEntity.kt#L7) · [Session persistence](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/repository/SessionRepository.kt#L74) · [Room builder](../apps/android-pos/app/src/main/java/com/icecreampost/pos/di/DatabaseModule.kt#L29) · [Keystore marker](../apps/android-pos/app/src/main/java/com/icecreampost/pos/security/FingerprintLoginStore.kt#L24) · [Backup rules](../apps/android-pos/app/src/main/res/xml/data_extraction_rules.xml)

**Business impact.** App-private storage and platform encryption reduce ordinary exposure. An attacker who can acquire app files on a rooted, compromised or debuggable device can obtain the still-valid bearer and offline business data. The fingerprint gate protects app navigation without encrypting the bearer itself.

**Remediation and implementation.** Store the bearer separately as AES-GCM ciphertext with a non-exportable Android Keystore key and a fresh nonce per write. Retain non-secret local session metadata independently so offline operations and recovery remain possible. Migrate away from the plaintext column with a plan for legacy database and WAL data, including session rotation. Evaluate the at-rest protection required for retained business records.

**Retest.** On a disposable handset or emulator, inspect synthetic SQLite and WAL data after migration and sign-out. Check process death, offline queues, key invalidation, biometric changes and app upgrade with queued transactions. Confirm reusable bearer text is absent from persistent artifacts and release logs.

### SEC-05 · Medium · Sales CSV output allows spreadsheet formula interpretation

**Classification** Confirmed in source. **Confidence** High. **OWASP** A05.

**Evidence.** The sale RPC accepts a nonblank `receipt_number` from POS and persists it without enforcing the normal `LOCAL-<UUID>` format. Web reports export that field. The CSV serializer doubles quotes and encloses the cell in quotes. CSV quoting preserves a text value as a field, but a spreadsheet can still interpret formula-prefixed contents. [Receipt validation and persistence](../supabase/migrations/202611030001_deduct_powder_on_open.sql#L109) · [Report mapping](../apps/web-ims/src/screens.tsx#L1265) · [CSV serializer](../apps/web-ims/src/lib/export.ts#L1) · [OWASP CSV Injection](https://owasp.org/www-community/attacks/CSV_Injection)

**Business impact.** A caller with an accepted sale upload can seed a value that is later interpreted when a report recipient opens the CSV. Consequences depend on the spreadsheet and its settings and include altered calculations or external-link prompts. No formula was executed.

**Remediation and implementation.** Validate receipt format at ingestion with an explicit compatibility path for historical imports. Treat exported strings as literals. Prefer a spreadsheet export with explicitly typed text cells when spreadsheet use is the default. If retaining CSV, neutralize formula-prefixed text according to the supported spreadsheet applications while preserving numeric columns and correct field escaping.

**Retest.** Generate a synthetic report containing harmless formula-like text, ordinary receipts, legacy receipts, quotes, separators, control characters and leading whitespace. Confirm supported spreadsheet applications render the text literally and preserve numeric totals.

### SEC-06 · Medium · Privileged web access lacks MFA and step-up authentication

**Remediation status** Implemented locally. A WebAuthn verifier records a successful passkey check against the current app session. Once the policy is enabled, Administrator sessions need a passkey to access the dashboard and verification within 15 minutes for privileged operations. The policy starts staged so administrators can enroll before enforcement. Hosted rollout and browser verification are pending. [Assurance migration](../supabase/migrations/202611040004_admin_passkey_assurance.sql) · [Passkey verifier](../supabase/functions/admin-passkey/index.ts)

**Classification** Confirmed in source. **Confidence** High. **OWASP** A06, A07.

**Evidence.** The web login RPC issues a 12-hour session after the password check. Its System Administrator path has no additional factor. Sensitive staff, activation and reset operations have no recency or elevated-authentication requirement beyond their role guard. The local rate-limit and new-password migration does not introduce MFA. [Web session issuance](../supabase/migrations/202611040001_harden_password_sign_in.sql#L142) · [Staff administration](../supabase/migrations/202611040001_harden_password_sign_in.sql#L210) · [Reset entry point](../supabase/migrations/202609290004_reset_financial_reporting.sql#L20)

**Business impact.** Once SEC-01 is fixed, a phished or otherwise compromised Administrator password would still grant broad management access. Password length and login throttling reduce guessing risk and do not establish possession of a second factor.

**Remediation and implementation.** Require a phishing-resistant second factor for Administrators. Record session assurance and recent step-up server-side. Check both for staff resets, stall reassignment, device recovery and destructive resets. Define an audited enrollment and recovery procedure.

**Retest.** Verify that password-only and stale-elevation sessions cannot perform privileged operations through direct APIs. Exercise enrollment, lost-factor recovery and verified step-up with synthetic Administrator accounts.

### SEC-07 · Medium · Sign-in security events are missing from application audit telemetry

**Classification** Confirmed application gap. **Confidence** High for source and unknown for hosted alerting. **OWASP** A09.

**Evidence.** The login functions update private failure and network-limit counters and create sessions. Successful sign-in clears account failure history. There is no durable application event for sign-in outcome or throttling. Existing database audit entries cover actions such as credentials resets, device recovery and data resets. No repository-managed application alert rules were identified. [Login counters and issuance](../supabase/migrations/202611040001_harden_password_sign_in.sql#L12) · [Credential reset audit](../supabase/migrations/202610060002_revoke_sessions_on_user_change.sql#L15) · [Device recovery audit](../supabase/migrations/202610010002_pos_activation_and_deduction_alignment.sql#L217)

**Business impact.** Operators have limited application evidence to correlate guessing, suspicious sessions or activity before a destructive action. Hosted gateway logs may provide separate evidence if configured and retained.

**Remediation and implementation.** Emit sanitized events for login success and failure, throttling, logout/revocation and denied high-impact operations. Record event time, request correlation ID, channel, outcome and an internal actor identifier when available. Apply retention and sampling controls. Send no passwords, activation codes, raw session tokens or request bodies to logs. Route actionable alerts to a monitored destination.

**Retest.** In staging, generate representative successful, failed and denied actions with synthetic data. Confirm audit events persist as intended, alerts reach the intended recipient, retention matches policy and secret values never appear in telemetry.

### SEC-08 · Medium · Build inputs lack immutable provenance controls

**Remediation status** PR checks pin GitHub Actions to upstream commit SHAs, install npm dependencies with the frozen lockfile, and compile both production variants with isolated configuration. The Android build pins the Gradle distribution checksum, verifies dependency SHA-256 hashes, and locks the production compile and runtime classpaths. Hosted PR checks, Android advisory review and deployed artifact provenance require verification. [PR workflow](../.github/workflows/pr-checks.yml) · [Gradle wrapper](../apps/android-pos/gradle/wrapper/gradle-wrapper.properties) · [Gradle verification](../apps/android-pos/gradle/verification-metadata.xml) · [Production lockfile](../apps/android-pos/app/gradle.lockfile)

**Classification** Confirmed configuration gap. **Confidence** High. **OWASP** A03, A08, M2.

**Evidence.** The PR workflow uses mutable major tags for third-party actions. Its token permissions are limited to read access, and the web install uses a frozen lockfile. Android restricts dependency repositories to approved upstream repositories. The Gradle wrapper specifies a version and HTTPS URL, with no expected distribution digest. The repository has no Gradle verification metadata or dependency lock files, and the workflow scans npm without a comparable Android advisory gate. [PR workflow](../.github/workflows/pr-checks.yml#L8) · [Gradle wrapper](../apps/android-pos/gradle/wrapper/gradle-wrapper.properties) · [Gradle repositories](../apps/android-pos/settings.gradle.kts) · [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use)

**Business impact.** Compromise or unintended movement of a CI action or build input could change what CI runs and undermine release assurance. The affected workflow is a PR check with restricted token permissions. The review found no evidence of a compromised dependency, runner or artifact.

**Remediation and implementation.** Pin Actions to reviewed full commit SHAs or adopt GitHub's workflow dependency lock mechanism where available. Set the official Gradle distribution SHA-256. Enable Gradle dependency verification and lock release-critical configurations. Generate and audit npm and Android build/runtime inventories, and tie accepted release artifacts to the reviewed commit.

**Retest.** Rebuild from a fresh runner with the verified inputs and confirm CI refuses a mismatched digest or unapproved dependency. Review artifact provenance and confirm advisory gates cover all intended configurations.

### SEC-09 · Low · Web development tooling resolves affected minimatch versions

**Classification** Confirmed advisory match. **Confidence** High for dependency versions and limited for reachability. **OWASP** A03.

**Evidence.** The npm registry audit identified `minimatch` `10.2.6` via TypeScript ESLint and `3.1.5` via ESLint, both marked development-only in the audit. The advisory lists versions below `11.0.1` and describes excessive resource use from specially shaped glob patterns in the affected preprocessing path. Upstream rates the advisory Moderate. This report rates the application impact Low because the observed paths are tooling and no app-controlled glob path was established. [Locked versions](../pnpm-lock.yaml#L1323) · [GHSA-g3hv-75wg-m249 / CVE-2026-118764](https://github.com/advisories/GHSA-g3hv-75wg-m249)

**Business impact.** An affected tooling path could exhaust runner resources if it reaches the vulnerable behavior with an untrusted glob. Browser runtime impact was not established. No load or denial-of-service test was run.

**Remediation and implementation.** Upgrade the parent lint tooling to compatible patched dependencies. Apply a narrow override only after verifying supported APIs and Node requirements. Keep the audit gate active during the change.

**Retest.** Verify the new lockfile, run the registry audit again, and confirm ESLint, TypeScript and PR builds still pass. Review any older branches or configs that continue to resolve the vulnerable version.

### SEC-10 · Medium · Android production build controls do not enforce release protections

**Classification** Confirmed repository configuration gap. **Confidence** High for build configuration and unknown for the installed production binary. **OWASP** M7, M8.

**Evidence.** The app Gradle file configures debug signing and has no checked-in production signing or release R8 configuration. The setup guide documents `assembleProductionDebug`. CI runs DevDebug checks only. The separate development artifact is correctly debug-signed and has the `.dev` package suffix. The production artifact and distributor were outside scope. [Android build](../apps/android-pos/app/build.gradle.kts#L29) · [Setup guide](../apps/android-pos/SETUP_VSCODE.md#L61) · [Android CI](../.github/workflows/pr-checks.yml#L63) · [Android app signing](https://developer.android.com/studio/publish/app-signing)

**Business impact.** A production-flavor debug artifact can be produced by the documented workflow when production values are supplied. If distributed, debug access would reduce protection for local POS data and credentials. Missing release provenance controls also make authenticity and upgrade continuity harder to establish.

**Remediation and implementation.** Block debuggable variants from production API access. Sign the approved release with a protected release key, enforce expected app ID and certificate fingerprint, and enable and test R8. Restrict production distribution to reviewed and hashed release artifacts. Preserve update compatibility with the currently installed signing identity after verifying it.

**Retest.** Inspect the final production APK with `aapt` and `apksigner`. Check the expected signer, app ID, version, exported components, backup policy, disabled debugging and absence of debug tooling. Rehearse in-place upgrade with offline sales and reject a debug artifact in CI.

### SEC-11 · Medium · Deployed web headers and API edge policy require verification

**Classification** Potential. **Confidence** High for the repository observation and unverified for hosted behavior. **OWASP** A02.

**Evidence.** The static web entry point and repository configuration show no source-managed policy for CSP, framing, HSTS or sensitive-response caching. The proposed host can set headers outside source. The app renders on a static origin and calls Supabase across origins. The local Supabase Auth configuration enables new signup even though the app uses separate password RPCs. Hosted domains, certificates, CORS, response headers, and Auth provider and signup settings were not inspected. [Web entry point](../apps/web-ims/index.html) · [Local Auth configuration](../supabase/config.toml#L154) · [Hosting plan](FIRST_RELEASE_DEPLOYMENT_PLAN.md) · [Cloudflare Pages headers](https://developers.cloudflare.com/pages/configuration/headers/)

**Business impact.** Missing protections could increase exposure to framing attacks, browser-side token theft, or unintended caching, depending on the deployed policy and any prerequisite browser vulnerability. A permissive CORS response by itself does not grant application authorization.

**Remediation and implementation.** Verify and manage CSP with strict `script-src`, `frame-ancestors`, `object-src`, and explicit API and image origins. Add suitable HSTS, `X-Content-Type-Options`, referrer policy and non-cacheable handling for authentication and sensitive data. Verify actual API behavior for trusted and untrusted origins. Confirm hosted Supabase Auth signup and provider settings match the intended custom authentication architecture.

**Retest.** On approved staging, inspect headers for the SPA, fallback routes, API login responses and Storage. Validate the HTTP-to-HTTPS path and TLS configuration. Use harmless tests to check embedding, required image loads, approved API calls and sensitive response caching.

### SEC-12 · Medium · Android authentication headers may survive cross-origin redirects

**Classification** Potential exposure. **Confidence** High for the client code path and unverified for upstream redirect reachability. **OWASP** M5.

**Evidence.** The application interceptor attaches `X-Session-Token`, `X-Device-Id` and `apikey`. The shared OkHttp client does not override redirect behavior. In the reviewed OkHttp 4.12.0 redirect implementation, cross-origin follow-ups remove the standard `Authorization` header while the custom headers remain on the cloned request. Some redirect statuses can also retain the request body. Whether the intended gateway ever returns such a redirect is unverified. Android's default trust handling is used in the reviewed source. [Network client](../apps/android-pos/app/src/main/java/com/icecreampost/pos/di/NetworkModule.kt#L29) · [Authentication interceptor](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/remote/interceptor/SupabaseHeadersInterceptor.kt#L13) · [OkHttp 4.12.0 redirect code](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt#L269) · [Android network security](https://developer.android.com/privacy-and-security/security-config)

**Business impact.** If the trusted API origin issues a cross-origin redirect, a follow-up request could send the bearer and device identifier to another origin. A body-preserving redirect on login could additionally expose credentials. This requires control of or influence over a trusted redirect response.

**Remediation and implementation.** Disable redirects on the credential-bearing client, require the configured HTTPS API origin, and keep public image fetching separate. If redirects are required, allow only explicitly approved same-origin locations and prevent authenticated requests from leaving the trusted origin.

**Retest.** In an isolated mock harness, issue same-origin, cross-origin and downgrade redirects with synthetic headers and bodies. Verify unapproved targets receive no credentials and invalid certificates and hostnames fail closed. Verify explicit cleartext denial on the oldest supported Android versions as well as the current release target.

### SEC-13 · Low · Legacy password cost remains in the verification path

**Classification** Potential exposure of existing accounts. **Confidence** High for supported formats and unverified for the hosted hash inventory. **OWASP** A04.

**Evidence.** Earlier account creation uses `gen_salt('bf')` without a cost parameter, which defaults to 6 in PostgreSQL 17. The new local policy sets cost 10 and adds a version marker for new or reset passwords. Existing unversioned hashes still verify directly, and successful sign-in does not upgrade them. [Historical provisioning](../supabase/migrations/20261007000201_role_based_sign_in_identifiers.sql#L158) · [Current local hashing and verification](../supabase/migrations/202611040001_harden_password_sign_in.sql#L64) · [PostgreSQL pgcrypto](https://www.postgresql.org/docs/17/pgcrypto.html) · [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)

**Business impact.** If legacy low-cost hashes exist and a database or backup is obtained, offline guessing is cheaper for those accounts. The assessment did not read any existing user hashes.

**Remediation and implementation.** Inventory only counts by hash version and work factor, benchmark the chosen adaptive hash, and migrate legacy credentials on verified sign-in or a managed reset. Coordinate hash-only upgrades with the current session-revocation trigger and test transactional ordering.

**Retest.** Use synthetic accounts to verify legacy login, upgrade or reset, revocation behavior, and continued login after migration. Confirm the legacy count falls to the agreed cutoff without exporting hash values.

### SEC-14 · Low · POS local-data lifecycle and identifier retention need verification

**Classification** Potential privacy and data-governance gap. **Confidence** Medium. **OWASP** M6.

**Evidence.** POS sends `ANDROID_ID` with API requests for activation and continues to keep stall business records for offline operations. Sign-out clears the session rather than the sales and inventory database. Backup and device transfer exclusions reduce copying risk. The reviewed code and release material do not establish a verified retention schedule or a device retirement workflow that accounts for queued records, cached data, device identifiers and operational logs. [Device identity](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/remote/interceptor/DeviceIdentity.kt#L9) · [Local database](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/local/database/CoolerzDatabase.kt) · [Backup rules](../apps/android-pos/app/src/main/res/xml/data_extraction_rules.xml)

**Business impact.** A retired, lost or reassigned POS could retain staff identifiers and business history beyond the intended period. Actual retention and device management policies require verification.

**Remediation and implementation.** Document the retention period and responsible operator for each data class. Preserve unsynced records until acknowledgement and reconciliation. Purge eligible synced history and caches on a controlled retirement path and revoke the device registration.

**Retest.** Reconcile and retire a disposable device. Confirm queued records remain available until accepted, and that the retirement procedure clears eligible local history and prevents further authenticated sync.

### SEC-15 · Medium · POS device authorization relies on a replayable identifier

**Remediation status** The POS signs each protected request with its enrolled Android Keystore key. The relay verifies the request signature, records the one-use nonce, and issues a short-lived permit bound to the key, bearer, and route. Device enrollment locks the stall to proof and preserves the device ID. SQL and relay tests covered copied identifiers, mismatched requests, expiry, replay, and key rotation. The hosted relay, handset upgrade, and global enforcement require verification. [Device proof migration](../supabase/migrations/202611040007_bind_pos_requests_to_device_keys.sql) · [Rollout](../supabase/README.md#deploy-pos-device-proof-and-credential-storage)

**Classification** Confirmed design weakness. **Confidence** High. **OWASP** M3.

**Evidence.** The Android app reads `ANDROID_ID` and sends it as `X-Device-Id`. The server's `active_pos_device()` compares the header value with a stored `hardware_id` after checking the Cashier role and stall. Login uses the same comparison to recognize a previously activated device. Neither path requires proof that the sender possesses a key held by the registered handset. [Device identity](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/remote/interceptor/DeviceIdentity.kt#L9) · [Request headers](../apps/android-pos/app/src/main/java/com/icecreampost/pos/data/remote/interceptor/SupabaseHeadersInterceptor.kt#L13) · [Active device check](../supabase/migrations/202610010002_pos_activation_and_deduction_alignment.sql#L57) · [POS login recognition](../supabase/migrations/202611040001_harden_password_sign_in.sql#L170)

**Business impact.** A caller who obtains a valid Cashier session and the registered identifier could appear to be the registered handset. The caller still needs a valid session, the correct identifier and any resource identifiers required by the endpoint. No handset impersonation was attempted.

**Remediation and implementation.** Enroll a device-held non-exportable key during activation and record the public key with the registration. Bind sensitive requests to that key using a fresh challenge or signed request proof with a narrow freshness window and replay detection. Revoke the old registration and key during replacement.

**Retest.** In approved staging with synthetic keys, show that a copied header alone, an expired or replayed challenge, and a revoked device key are denied. Confirm legitimate offline uploads, retries and device replacement still work.

## 8. Risk severity summary

| Severity | Confirmed | Potential | Total |
| --- | ---: | ---: | ---: |
| Critical | 1 | 0 | 1 |
| High | 1 | 0 | 1 |
| Medium | 8 | 2 | 10 |
| Low | 1 | 2 | 3 |
| **Total** | **11** | **4** | **15** |

Informational observations include RLS on inspected public tables, restricted direct access to account/session tables, hashed server-side session tokens, password throttling in the local migration set, Android backup exclusions, and constrained environment selection for development builds. These controls have independent value and do not resolve the findings above.

## 9. Recommended remediation and secure implementation patterns

**Database authorization first.** Implement the guard on each protected entry point and make shared role predicates total. Keep the actual function grants aligned with the custom-session design. The SQL below illustrates the core pattern for a future authorized change.

```sql
create or replace function public.is_system_admin()
returns boolean
language sql stable security definer
set search_path = pg_catalog
as $$
  select public.current_app_role() is not distinct from 'system_admin'::public.app_role;
$$;

-- At a privileged RPC entry point, before resource reads or writes.
if public.can_manage_stall(p_stall_id) is not true then
  raise exception 'FORBIDDEN' using errcode = '42501';
end if;
```

**Financial validation next.** Authorize the account, stall and device first. Resolve the catalog revision from an immutable server-issued snapshot for that stall. Compute prices, expected components and costs using that revision, then validate and persist the sale, replay record and ledger updates in one transaction. Route unsupported historical revisions to reconciliation.

**Constrain credential-bearing transport.** Apply HTTPS origin validation at configuration and turn off redirect following on the authenticated API client. Keep the public image client separate. This is an illustrative Kotlin configuration fragment.

```kotlin
val apiClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor(authenticatedApiHeaders)
    .build()
```

## 10. Security improvement roadmap

| Priority | Work | Release evidence |
| --- | --- | --- |
| P0 | Fix SEC-01 and inventory all 55 callable public security-definer functions. Establish whether any hosted environment contains the affected definitions. If exposure is established, contain it and review existing logs and privileged changes. | Fail-closed negative tests for unauthenticated and wrong-role callers, verified staged catalog, and current hosted migration/definition inventory. |
| P1 | Fix SEC-02 before relying on POS numbers for unattended financial reporting. Implement SEC-03, SEC-04, SEC-06 and SEC-15 alongside the session and device changes. | Valid offline upload and upgrade tests, invalid financial-input tests with zero side effects, logout revocation, and verified admin and device authentication. |
| P1 release gate | Validate SEC-10 and SEC-11 before public distribution. Implement event coverage and alerts for SEC-07. | Verified production artifact signing and manifest, hosted headers and TLS evidence, and a successful synthetic security alert. |
| P2 | Complete SEC-05, SEC-08, SEC-09, SEC-12, SEC-13 and SEC-14. Repeat dependency and privacy checks at release. | Spreadsheet-safe exports, reproducible build inputs, a clean or risk-accepted audit, tested redirects, legacy migration metrics and documented device retirement. |

## 11. Areas requiring manual validation

| Area | Required evidence |
| --- | --- |
| Supabase development and production | Authenticate the pinned CLI after verifying the environment. Inspect linked migration history, actual `pg_proc` definitions and grants, RLS, exposed schemas and Storage configuration. Use fixture accounts on approved staging for negative API tests. Verify the trusted gateway IP header, HTTP 429 behavior and account cooldown under bounded normal test traffic. |
| Web hosting | Confirm the deployed hostname and backend configuration. Inspect TLS, response headers, CORS, framing and caching. Run desktop and iPhone PWA sign-in, sign-out and session expiry on staging. |
| Production POS | Obtain the release artifact and approved handset. Verify signer, manifest and code shrinker evidence. Validate TLS trust, redirects, log redaction, keystore behavior, device binding and synthetic storage artifacts. |
| Data and operations | Verify role assignment, incident alerts, backup and restore, credential recovery, and device retirement with the operators responsible. Review retention of account, device, network and financial records. |
| Build and secrets | Review repository settings, branch protection, deployment provenance, and CI and hosting secrets. Run approved secret history and release binary scans. Extend dependency scanning to Android build plugins, the Android SDK and non-Maven inputs. |

## 12. Final assessment conclusion

The assessed revision has a verified authorization defect that warrants blocking release. Remediate SEC-01, expand the negative authorization suite, and confirm actual hosted definitions before drawing conclusions about exposure. Resolve the independent POS sale integrity issue before treating synchronized values as authoritative financial records. Hosted configuration, the production Android artifact and operational controls still require the evidence listed above. This assessment generated a report and did not modify application source or production data.
