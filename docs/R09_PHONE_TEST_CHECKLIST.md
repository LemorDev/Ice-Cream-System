# R09 — actual development phone checks

Software preparation passed on 13 October 2026. See
[software evidence and the exact APK](R09_SOFTWARE_TEST_EVIDENCE.md). Fill in the
actual phone results here; software tests do not complete R09.

The optional [Artemis case runner](R09_ARTEMIS_AUTOMATION.md) can drive and
record each phone walkthrough. Review its evidence and enter actual results
in this table; an Artemis transcript alone is not a passing result.

Use **Coolerz POS (DEV)**, package `com.icecreampost.pos.dev`, connected to
development project `fhyqrgxwdqlyzxsnpthr`. Record the phone model, Android
version, APK hash, tester, and testing time. Before replacing an installed
build, record any queued sales and receipts and keep their receipt numbers.

Preparation observed on 13 October 2026 at about 16:33 Singapore time:
RMX2189, Android 11, candidate APK SHA-256
`1A10D6DAD3637FB9AA73C87C85CD006DB473E03AF2E8D40F1A491F7DD668F354`.
No POS package was installed before the candidate, so there was no prior queue
on this phone. The tester's name has not been recorded. The signed-in home
screen was observed with the stall closed and zero sales/orders. This is
preparation evidence, not completion of the cases below.

## Install the fixed build in place

Transfer `apps/android-pos/app/build/r09-candidate/coolerz-pos-dev-r09-20261013.apk`
to the phone and install it as an update. If using USB from repository root:

```powershell
adb devices -l
adb install -r '.\apps\android-pos\app\build\r09-candidate\coolerz-pos-dev-r09-20261013.apk'
```

Keep the app's local data. If Android reports a signature mismatch, stop the
update and retain the installed app and queue; use its original signing key to
build a compatible update. The configured candidate certificate is recorded in
the software evidence.

## Walkthrough and result record

Before selling, note starting stock, menu price, ingredient recipe quantities,
unit costs, and opening cash. Use test data in development. Preserve an existing
open day and its queue rather than clearing them to start this checklist.

| Case | Action and expected result | Actual result / receipt IDs / tester / time |
| --- | --- | --- |
| R09-01 Login regression | With internet connected, sign in with stall code and Cashier employee ID. An already registered phone reuses its device registration. No JSON-null error appears. | **Passed on this development phone, 13 Oct.** Tester signed in locally after a fresh app install on the same physical Realme phone used for an earlier manual test. Artemis inspected the signed-in home screen at about 16:58 and captured “Hello, Obing,” the development banner, stall closed, ₱0.00 sales, and 0 orders, with no visible JSON-null or recovery error. IMS's active device and earlier server day both use device ID `2504c8e5-6045-460f-912d-1ba710dce1fa`, which matches the current phone's local device ID. |
| R09-02 Catalog and stock | Sync. Verify the selected stall's products, recipes, prices, and stock against IMS; another stall's items must not appear. | **Partial, 13 Oct.** Artemis phone navigation showed one sellable product, Large Ice Cream Twirl at ₱25.00, in stock. Its Inventory view matched the selected Main Ice Cream Stall's stock at the time. A later read-only phone database snapshot on the updated APK shows all five active catalog products scoped to the signed-in stall, with the Twirl recipe exactly 1 Large Ice Cream Cone plus 1 g Vanila Powder, matching IMS; current phone/IMS stock is 294 large cones, 300 small cones, 11,000 g Ube Powder, and 9,994 g Vanila Powder. The phone UI does not expose recipe quantities. Development IMS currently has only one active stall, so the cross-stall isolation check needs a second-stall fixture before it can pass. |
| R09-03 Open, sell, sync | Open the operating day or resume its existing day. Sell one known menu item. Receipt, change, ingredient use, and IMS revenue/COGS match the recorded recipe. The sale appears once and its queue acknowledgment clears. | **Blocked as a regression test; clean retest skipped at tester's request.** Artemis saved exactly one Large Ice Cream Twirl sale at ₱25.00, cash received ₱50.00, change ₱25.00, receipt `LOCAL-2ECB95C2-4F53-4E59-ACA5-509D942CE8D1`, COGS ₱2.02, and −1 g Vanila Powder/−1 Large Ice Cream Cone. The app created a new local day instead of resuming the existing IMS day, so automatic sync initially failed. With tester approval, the local day and its sale/ingredient links were reconciled using a validated backup. The receipt then appeared exactly once in IMS. A day-resume fix was built and installed, and its migration was verified in development remote history. The tester declined the clean same-device fresh-install retest because it would clear the phone's current app data and need one additional test sale. See [R09_DAY_RECONCILIATION.md](R09_DAY_RECONCILIATION.md). |
| R09-04 Deductions | Record a known till deduction, distinguishing a new expense from payment of already-booked overhead. Expected cash decreases once; profit changes only for the new expense. Compare with IMS. | **Passed on the updated development phone, 13 Oct at about 18:29 Singapore time.** Artemis recorded one ₱10.00 `R09 test expense` with “Additional expense in profit” checked. Phone cash summary changed from ₱75.00 sales/₱0.00 deductions/₱75.00 expected cash to ₱75.00 sales/₱10.00 deductions/₱65.00 expected cash. IMS Daily Close & Profit shows one matching ₱10.00 POS cash deduction and ₱10.00 additional POS expense, with ₱75.00 gross, ₱6.06 COGS, and net profit −₱684.39. Read-only phone snapshot shows exactly one deduction (`acb0a960-cf54-46cb-918d-3fc67de029ed`) for this server day, profit flag set, synced with no error; sale and ingredient queues are empty. |
| R09-05 Stock receipt | Receive a known stock quantity. For tracked powder use whole packs. Total grams and sealed pack count increase once after sync/retry. | **Passed, 13 Oct at about 18:56 Singapore time.** With tester approval, development Ube Powder was set to 1,000g pack tracking and a baseline of ten sealed packs/0g hopper at 10,000g total. The phone synced this setup. Artemis received exactly one sealed Ube pack after a review screen showed +1,000g; the phone and IMS now show 11,000g total, 11 sealed packs, 0g hopper. Read-only phone SQLite shows exactly one new `receive` row for +1,000g/+1 sealed pack, synced with no error; queue remains empty. |
| R09-06 Powder opening | For tracked powder, open one pack. Sealed stock decreases by one and hopper increases by 1,000g; total grams and COGS do not change. Missing opening logs may yield a negative hopper reminder while total stock remains. | **Passed, 13 Oct at about 18:58 Singapore time.** Artemis opened exactly one Ube pack from 11 sealed packs/0g hopper. Phone and IMS show 10 sealed packs/1,000g hopper, with total stock still 11,000g. A read-only phone snapshot shows one `pack_unsealed` row, 0g total delta, −1 sealed pack, zero cost, synced with no error; queue is empty. Day COGS remains ₱10.10. |
| R09-07 Offline and restart | Disable internet, save two known sales, record their receipt IDs and queue count, then force-close and reopen the app. Both records and the correct stock remain locally. | **Passed, 13 Oct.** At about 18:34–18:36 Singapore time, Artemis saved exactly two separate offline Large Ice Cream Twirl sales, `LOCAL-06EBFA67-3EFC-4994-89A5-DC0BE9D6CE11` and `LOCAL-45A16A27-E4B1-406C-BA3F-405F21CD3BF8`, each ₱25.00 with ₱50.00 tender and ₱25.00 change. It force-stopped and relaunched only the dev app. Wi-Fi and mobile data remained off. A read-only SQLite snapshot after restart passed integrity and showed both sales and four ingredient rows pending with no sync errors; stock was 295 large cones and 9,995 g Vanila Powder. The app displayed sign-in offline after restart. Once the tester reauthenticated online, History showed both receipts and Inventory showed the expected quantities. No sale was repeated. |
| R09-08 Reconnect and retry | Restore internet; interrupt an upload by losing connectivity, then restore it and use sync again. Each accepted receipt appears once in IMS. Queue reaches zero after acknowledgment; failed uploads remain visible until resolved. | **Partial: reconnect and uniqueness passed; interrupted-upload retry skipped at tester's request.** Wi-Fi was restored with mobile data still off. After the tester signed in on the phone, the two R09-07 receipts uploaded automatically. A read-only phone snapshot shows both sales and all four ingredient rows synced with no errors, and History labels both receipts “Synced.” IMS Transactions shows each exact receipt once, four total receipts, ₱125.00 gross and ₱10.10 COGS; IMS and phone stock both show 295 large cones and 9,995 g Vanila Powder. The queue was already clear before a controlled upload interruption could be attempted. The tester declined a further offline sale for that test. |
| R09-09 Session expiry | When the test session expires, test the documented offline-session behavior and subsequent app relaunch/sign-in. Existing queued records remain intact and upload once after authentication. The automated suite already covers expiry; record actual handset evidence separately. | **Pending actual handset expiry.** The current Cashier session expires around 07:27 Singapore time on 14 Oct. The queue is empty, and the tester declined another offline sale, so queued-record survival through expiry cannot be exercised in this run. The automated expiry suite remains separate evidence. |
| R09-10 In-place update with pending data | On the prior compatible dev build, record a nonempty queue before installing the candidate with the same certificate. After updating, receipts, day, stock, and queue survive; reconnect and verify each receipt once in IMS. If no prior pending queue was available, leave this case pending and arrange a separate update rehearsal. | **Passed, 13 Oct.** With tester approval, Artemis saved exactly one offline Large Ice Cream Twirl sale, receipt `LOCAL-758CD3B0-0945-460D-9E33-FFC54C0CD76B`, ₱25.00 total, ₱50.00 tender, ₱25.00 change. A verified pre-update phone backup showed that receipt and its two ingredient movements as the only pending rows, linked to the existing open day; stock was 294 large cones and 9,994g Vanila Powder. Both radios were off. A compatible APK with the same signing certificate was installed in place and its installed SHA-256 verified as `B13FFF89360E0E781CCDAC4E03CDACFEBD39D31E8EBE2BD1FA9C486AC4EF347F`. The post-update offline database archive was byte-identical to the pre-update archive, including day, queue, and stock. Artemis opened the updated app offline and reached sign-in without another sale or sync. After Wi-Fi returned and the tester reauthenticated, a read-only phone snapshot showed the receipt and both ingredient movements synced without error and the queue empty. IMS Transactions showed the exact receipt once. The existing day showed five receipts, ₱150.00 gross, ₱12.12 COGS, 294 large cones, and 9,994g Vanila Powder; the earlier ₱10.00 deduction remained once. |
| R09-11 Close and reconcile | After queue acknowledgments, close the operating day. Compare receipt count, gross sales, COGS, cash/profit deductions, waste, expected cash, and counted cash with IMS. | **Blocked at closing-record sync, 13 Oct.** The tester approved closing this development day with ₱140.00 physically counted. Preflight showed no pending sales, ingredients, or deductions. Artemis reviewed and confirmed one close on the original 2:04 AM day. The phone and IMS show the day closed at about 7:39 PM with ₱150.00 gross, ₱12.12 COGS, ₱10.00 deduction, ₱0.00 waste, ₱140.00 expected cash, and ₱140.00 counted cash. The phone has exactly one local daily closing row, still unsynced with `CLOSING_RECONCILIATION_MISMATCH`; it has not been retried. The resumed day contains one server-only opening sale and its ingredient movements, so the phone's closing upload sends local row counts below IMS counts even though amounts match. A guarded fix and migration are prepared; see [R09_CLOSING_RECONCILIATION.md](R09_CLOSING_RECONCILIATION.md). |

Example for the powder case, if the chosen recipe uses 45g per serving: with
3,000g total and three sealed packs, a sale produces 2,955g total and a -45g
hopper. Opening one pack then produces two sealed packs and a 955g hopper,
with the total still 2,955g. Packaging consumption follows its own recipe.

The tester clarified that development IMS's open 13 October day was created
earlier by manual testing on this same physical Realme phone, before Artemis.
The earlier queue was empty. The current app installation initially created
local day ID `287bb0c0-6de1-42e9-9c96-9165945f6981`, which conflicted with IMS's server day ID
`adf1fb4a-5bca-48ff-8fad-d3d9f2e2a211`, with the same POS device ID as the
current phone. The existing day opened at 2:04 AM with a different synced ₱50.00
receipt and ₱4.04 COGS. The approved reconciliation preserved that earlier
receipt, linked the new ₱25.00 receipt to the existing day, and cleared the
phone's queue. The temporary Artemis sync hold was removed after verification;
the backup and exact checks are in [R09_DAY_RECONCILIATION.md](R09_DAY_RECONCILIATION.md).

Record any blocking defect with the case number, expected/actual result,
receipt ID, queue state, and screenshot. Do not mark R09 complete until all
cases pass and the update preservation requirement has actual phone evidence.
