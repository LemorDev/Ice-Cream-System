# R06 — release blocker repairs

Source: [R05 audit](R05_RELEASE_RISK_AUDIT.md), accepted [v1 scope](FIRST_RELEASE_SCOPE.md). Each row is a separate release gate. Check a row only after its code and named verification pass; the draft PR must remain unmerged while any row is open. Server schema and RPC changes precede compatible POS and IMS changes, followed by cross-client verification.

| Gate | Repair and acceptance evidence | Status |
| --- | --- | --- |
| R06-01 / R05-08 | Use the opening operating-day ID for sales, deductions, waste, reversals, closing, and Owner reports across a midnight shift; test Manila midnight and UTC boundary. | [ ] |
| R06-02 / R05-09 | Snapshot component quantities and unit costs at sale; preserve historical COGS/profit after cost edits and archives in POS, server and IMS. | [ ] |
| R06-03 / R05-11 | Upload immutable sale-time recipe usage; test an offline sale followed by a recipe edit before replay. | [ ] |
| R06-04 / R05-12 | Cashier POS full refund/void with reason, restock or waste, cash returned, idempotent offline queue, original-day correction, current-till cash, and Owner audit view. | [ ] |
| R06-05 / R05-16 | Gate closing on acknowledged day sales, reversals, inventory and deductions; reconcile client/server count and totals; test late retry and permanent failure. | [ ] |
| R06-06 / R05-05 | Page or aggregate IMS stock, sales, items, days, closings and deductions completely with stable alignment; test past page limits. | [ ] |
| R06-07 / R05-06 | Page Android products, recipes and ledger without timestamp ties or interrupted pages losing rows. | [ ] |
| R06-08 / R05-13 | Accept duplicates only by identical transaction identity and payload; use collision-resistant receipt IDs; test equal-total conflicting receipts. | [ ] |
| R06-09 / R05-14 | Serialize manual and worker sync and verify overlap, timeout after commit and replay. | [ ] |
| R06-10 / R05-02 | Bind local POS data to one stall and reject cross-stall sign-in before any display or sync; test queued and synced records. | [ ] |
| R06-11 / R05-04 | Revoke sessions on administrator password reset, handle lockout recovery, and rehearse documented identity verification. | [ ] |

The lost-phone offline queue remains the [R05-18 accepted limitation](R05_RELEASE_RISK_AUDIT.md). The Cashier and Owner instructions must say to continue on numbered paper, identify what the server actually knows, and reconcile each paper sale before resuming electronic sales. Physical-phone and live development-backend checks remain R08–R09 gates even after code checks here.

## Incremental evidence, 6 October 2026

- **R06-08 code repaired:** the server stores the original JSON sale upload and only acknowledges an identical ID, receipt and payload on replay. A receipt collision, changed item document, or legacy sale without a recorded upload identity returns `IDEMPOTENCY_CONFLICT`. The POS now uses the transaction UUID in the receipt number. `r06_replay_and_reset.sql` passed in the isolated migration runner, including equal-total conflicts and unchanged stock after retry. Live development-backend verification is still required in R08.
- **R06-09 code repaired:** a repository mutex serializes immediate, manual and worker calls in one app process. Android unit tests cover simultaneous callers and the existing timeout/retry path. Process-death and real Room queue verification remain R09.
- **R06-10 code repaired:** sign-in and stored-session restoration refuse another stall when a binding or any persisted products, days, sales or inventory belong to the first stall. Sign-out retains the binding. Unit tests cover both old data and a bound empty queue; R09 must verify this on a physical phone.
- **R06-11 server repair:** a password or access change revokes that user's sessions; a password reset also clears lockout and records `user.credentials_reset`. The isolated SQL fixture passed. The [Cashier/Owner instructions](R06_CASHIER_OWNER_OPERATIONS.md) give the recovery and identity-verification steps. Rehearse with the actual administrator in R18 before checking this gate.
- **R06-05 partial guard:** the POS refuses to upload a daily closing while any sale through its close time or any inventory movement remains unsynced; a unit test covers a permanently rejected sale. Server count/total reconciliation and full operating-day ownership remain open.

All other gates remain open. These repairs do not authorize a pilot or merging the draft PR.
