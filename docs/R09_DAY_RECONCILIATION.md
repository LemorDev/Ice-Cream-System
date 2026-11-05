# R09 development operating-day reconciliation proposal

**Status: completed and verified on 13 October 2026.** The development IMS day
`adf1fb4a-5bca-48ff-8fad-d3d9f2e2a211` and the Artemis phone's local day
`287bb0c0-6de1-42e9-9c96-9165945f6981` belong to the same stall, date,
and POS device `2504c8e5-6045-460f-912d-1ba710dce1fa`. A fresh app install
lost the earlier local day identity. The phone holds one unsynced sale,
`LOCAL-2ECB95C2-4F53-4E59-ACA5-509D942CE8D1`, for ₱25.00 with ₱2.02
COGS and two ingredient movements. IMS holds a different earlier synced sale
for ₱50.00 with ₱4.04 COGS.

The tester explicitly approved this **local app-data reconciliation** and one
sync of the existing sale. It does not create another sale or change the
earlier IMS receipt.

At 17:49 local time, the stopped phone's DB, WAL, and SHM were archived under
the ignored local directory
`apps/android-pos/.local/r09-artemis/reconciliation-20261013T094906Z/`.
The original archive SHA-256 is
`77251dfe3b90e4d14f66472b43d87ad0bf4c88749d225ba8bcb8fe53ba027515`;
the copied database passed `PRAGMA integrity_check`. A patched copy changed
exactly one day, one sale link, and two ingredient links, then passed integrity
and accounting checks. Its SHA-256 is
`5c2d7a91b7bf421b4f994643388568f55d5f1bf1f533362a122f1888b7fff1e3`.
That copy was installed in the development app. The original DB and WAL also
remain in the app's private `files/r09-before-*` backups. The POS restarted
to the Cashier sign-in screen and initially IMS still showed only the earlier
₱50 receipt. The tester signed in locally; the app then uploaded the pending
receipt automatically. The temporary transfer copy in `/data/local/tmp` was
removed after verification.

## Required checks immediately before any repair

1. Freeze further phone/IMS writes for this stall. Confirm the phone's current
   pending set is exactly one day, one sale, and its two sale ingredient rows;
   no deduction, receiving, reversal, or closing is pending. Stop on any extra
   record or changed ID.
2. Confirm IMS still shows the same open server day, active device ID, earlier
   ₱50 sale, one order, ₱4.04 COGS, and stock of 298 large cones and 9,998 g
   Vanila Powder. Confirm the new ₱25 receipt is absent. Stop if any value has
   changed.
3. Capture the server day's exact `opened_at`, `cashier_id`, and opening notes;
   the phone's current day and receipt snapshots; and a consistent copy of the
   app database, including its write-ahead log. Keep the backup in the ignored
   local evidence directory with a SHA-256 digest. Verify the copied database
   passes SQLite integrity checks before touching the phone.

## Proposed local transaction

On a verified copy first, then on the stopped app's database in one SQLite
transaction: replace the local day ID with the existing server day ID; copy
the server day opening identity and time; set server-known baseline totals to
₱50.00 sales, one order, ₱4.04 COGS, and zero deductions/waste; update the
single local sale's `businessDayId` and its two ingredient rows' `businessDayId`
to that server day; clear only their day-identity sync errors. Keep the sale,
receipt, item, ingredient, product, stock, device, and occurrence IDs/values
unchanged. Mark the day as already present on the server, so its opening record
is not uploaded as a new day. Recheck local integrity, row counts, links, and
totals before restarting the app.

## One upload and acceptance criteria

Sync the existing pending receipt once. Stop on an ambiguous result; inspect
IMS by exact receipt and transaction IDs before retrying. The expected IMS day
after one accepted upload is **two receipts, ₱75.00 gross, ₱6.06 COGS**, stock
of **297 large cones and 9,997 g Vanila Powder**, with the new receipt and its
two ingredient movements appearing exactly once. The phone should show the
same two-order/₱75 baseline plus local sale total and an empty queue. Keep
the pre-repair backup and `SYNC_HOLD.json` until these checks pass. Only then
resume R09-04 onward and rehearse an in-place update on a separate prior build
with a known pending queue for R09-10.

Do not use IMS “Close from IMS,” clear app data, repeat checkout, or modify
the server day ID as a shortcut. None of those actions reconciles this pending
local sale.

## Verified result

The exact new receipt appeared once in IMS with one Large Ice Cream Twirl at
₱25.00, ₱50.00 cash received, and ₱25.00 change. IMS Transactions shows exactly
two receipts for the stall: the earlier ₱50.00 receipt and this ₱25.00 receipt.
Daily Close & Profit shows ₱75.00 gross, ₱6.06 COGS, no deductions or waste,
297 large cones, and 9,997 g Vanila Powder. The POS home shows the original
2:04 AM opening time, ₱75.00 sales, and two orders. Its Sync status says synced.
A second read-only phone database snapshot at
`apps/android-pos/.local/r09-artemis/reconciliation-20261013T095941Z/`
passed SQLite integrity checks and shows the day, sale, and both ingredient
movements under the server day ID, each marked synced with no error. The
`SYNC_HOLD.json` runner pause was removed only after these checks passed.
