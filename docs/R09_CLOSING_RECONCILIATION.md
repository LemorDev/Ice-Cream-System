# R09-11 recovered-day closing reconciliation

## Observed development state, 13 October 2026

The linked Supabase project is `fhyqrgxwdqlyzxsnpthr`. The same Realme POS
resumed server day `adf1fb4a-5bca-48ff-8fad-d3d9f2e2a211`, opened at 2:04 AM.
One original ₱50.00 sale and its ingredient movements are present in IMS but
are absent from the phone's local transaction history. The phone keeps a
frozen recovery baseline for their amounts and order count.

The tester approved closing the day with ₱140.00 counted cash. Before closing,
the phone database had no pending sale, ingredient, or deduction rows. Artemis
confirmed the close once at about 7:39 PM. Phone and IMS both show the day
closed with ₱150.00 gross, ₱12.12 COGS, ₱0.00 waste, one ₱10.00 cash and profit
deduction, ₱140.00 expected cash, and ₱140.00 counted cash. The phone has
exactly one local closing row, ID `491a7c3b-5c88-4b37-81e7-0397d759d6f5`,
which is unsynced with `CLOSING_RECONCILIATION_MISMATCH`. No closing retry or
second close has been attempted. Read-only phone backups and Artemis evidence
are under ignored `apps/android-pos/.local/r09-artemis/`.

`push_daily_store_closing_v2` verifies both amounts and row counts. The local
closing uploader sends four sales and thirteen day ledger movements; IMS has
five sales and includes the two server-only movements from the original sale.
The amount baseline is already included correctly in the phone's close. The
count mismatch prevents the closing record from being accepted. The server
operating-day row was already closed by the preceding day sync.

## Prepared fix

Migration `202610130004_recovered_day_closing_counts.sql` adds a read-only RPC
scoped to the authenticated Cashier, active device, selected stall, and closed
day. It returns server sale, movement, deduction, and reversal counts. The
Android uploader uses those counts only for a recovered day, and refuses to
submit if the server sale count differs from the local count plus the frozen
known opening order count or if any server count is below the local count.
The existing server closing RPC still validates gross, COGS, waste, deduction,
expected cash, day identity, device identity, and the returned counts before
accepting the original closing ID.

Focused `SyncRepositoryTest` tests cover the recovered-day success and count
mismatch paths; `:app:testDevDebugUnitTest --tests
"com.icecreampost.pos.data.repository.SyncRepositoryTest"` passed. The
`devDebug` APK built successfully and is frozen under ignored
`apps/android-pos/app/build/r09-closing-recovery/`, SHA-256
`6BF4AAABD19630D7CFD4ED99E3F317477B50B766FA2AEE8815F0655BC1169E12`.
Its signing certificate matches the installed dev APK.

The linked project reference was confirmed as `fhyqrgxwdqlyzxsnpthr`.
Before deployment, remote migration history showed `202610130003` applied and
`202610130004` pending, and the dry run listed **only** `202610130004`. With
tester approval, `supabase db push` applied `202610130004`; a subsequent
`supabase migration list --linked` shows it in both local and remote history.

From the repository root, these are the exact PowerShell commands to inspect,
preview, apply, and verify the migration:

```powershell
Get-Content .\supabase\.temp\project-ref
& .\node_modules\.bin\supabase.cmd migration list --linked
& .\node_modules\.bin\supabase.cmd db push --dry-run
& .\node_modules\.bin\supabase.cmd db push
& .\node_modules\.bin\supabase.cmd migration list --linked
```

Before applying, review the target project and ensure only migration
`202610130004` is pending. Do not use `db reset --linked` or run SQL fixtures
against the linked project.

The phone database was backed up before installation, and the compatible APK
was installed with `adb install -r`. The installed APK hash matches the
frozen build. A post-install backup was byte-identical to the pre-install
backup, including the one pending closing. The updated app is at sign-in;
Cashier reauthentication and one-time closing acknowledgment remain to be
verified. Do not create another sale or reopen the day.
