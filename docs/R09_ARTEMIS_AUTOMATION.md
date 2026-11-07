# R09 phone walkthrough with Google Artemis

This runner turns each row of [the phone checklist](R09_PHONE_TEST_CHECKLIST.md)
into a separate Artemis phone task. It records the Artemis transcript, device
identity, candidate APK hash, and an end-of-case screenshot. Results go under
`apps/android-pos/.local/r09-artemis/` (ignored by Git). A successful CLI run
starts as `PENDING_HUMAN_REVIEW`; the tester checks the phone and IMS before
recording a reviewed verdict and receipt IDs in the checklist.

Artemis is an Android UI agent. It can navigate the phone, perform the test
actions, and collect traces, but its own success message cannot prove backend
uniqueness, accounting totals, or that an old build's data survived an update.
The runner never resets app data, installs an APK, edits the checklist, or
repeats a case automatically.

## Prepare the phone and Artemis

1. Connect **one** authorized USB-debugging phone. Run `adb devices -l`; it
   must show `device`. Ensure it is the development handset and that its
   existing receipts and queue have been recorded before any installation.
2. Confirm the exact candidate and development project in
   [the software evidence](R09_SOFTWARE_TEST_EVIDENCE.md). If it is not
   installed yet, verify the installed build's signing certificate matches the
   candidate before the in-place update. Then follow the checklist's
   `adb install -r` command. Stop on a signature mismatch; never uninstall
   merely to make this rehearsal pass.
3. Use the existing checkout at `apps/android-pos/.local/artemis`, or clone
   [google/artemis](https://github.com/google/artemis) to another ignored local
   directory. Configure a model provider key in its local `.env`. Do not paste
   the key into the runner config or this repository's tracked files. The
   current checkout has a `.venv`; the runner uses it directly. For a new
   checkout, install Artemis dependencies with `uv` first. Artemis's README
   documents the Windows setup and CLI. Its `start.bat` also installs global
   integrations, so it is unnecessary for this runner.
4. Copy the input template to an ignored local path and fill in **development
   test values only**. Do not put a password, activation code, API key, or
   service-role key in this file. The cashier enters a password on the phone.

```powershell
Set-Location 'C:\Desktop Apps and Files\Ice cream system'
New-Item -ItemType Directory -Force .\apps\android-pos\.local | Out-Null
Copy-Item .\apps\android-pos\scripts\r09-artemis.example.json .\apps\android-pos\.local\r09-artemis-input.json
```

Use an existing open day and queue; do not clear either to prepare the test. Fill the receipt
IDs from R09-07 before running R09-08. For R09-10, use a receipt that was
pending on the **prior** compatible dev build before the candidate update.

## Run one case at a time

Review the generated prompt first. This workspace's Artemis checkout is at
`apps/android-pos/.local/artemis`. The runner checks the APK checksum, manifest's
development project/package, one connected authorized phone, and installed
`com.icecreampost.pos.dev` before sending any task.
It also hashes the installed `base.apk` and requires an exact match. The
original candidate is the default; after installing another build, pass its
frozen APK and matching manifest with `--apk` and `--manifest`.

```powershell
Set-Location 'C:\Desktop Apps and Files\Ice cream system'
node .\apps\android-pos\scripts\r09-artemis.mjs plan R09-03 --config .\apps\android-pos\.local\r09-artemis-input.json
node .\apps\android-pos\scripts\r09-artemis.mjs run R09-03 --config .\apps\android-pos\.local\r09-artemis-input.json --artemis .\apps\android-pos\.local\artemis --execute
node .\apps\android-pos\scripts\r09-artemis.mjs status
```

Run `plan` without a case to see all eleven tasks and missing inputs. Each
`run` dispatches exactly one case. The runner does not enforce case order, so
inspect the previous result before proceeding. If R09-10 has a pre-update
pending queue, capture its post-update evidence before any login or sync can
acknowledge it, even though the table lists R09-10 later. `--execute`
acknowledges that the selected case can create
development sales, deductions, stock records, pack openings, or a day close.
If a save or sync result is ambiguous, inspect the existing receipt or record
before rerunning; a second `run` is a new action. The runner writes an
`IN_PROGRESS` record before dispatch and refuses a repeat unless `--rerun` is
supplied. A stranded `IN_PROGRESS` record means the action may have happened.
Watch the phone or Artemis dashboard during mutating cases. Artemis can take
extra UI actions despite a prompt limit; stop its session at the first
unexpected queue error and inspect the existing record before any further run.

R09-01 requires the tester to enter the cashier password locally. R09-09 waits
for a genuine session expiry; do not change the clock to manufacture one.
R09-10 requires a separate, compatible prior build and a queue recorded before
the update; the runner only inspects after the update. R09-11 closes the day,
so enter counted cash and run it only after all queue acknowledgments.

For each case, review the `.json`, `.txt`, and optional `.png` evidence, plus
any `.steps.json`, `.artemis-report.md`, and Artemis trace. A report can contain
unsupported assertions; check its steps and the actual phone screen. Compare the R09-02 through R09-06, R09-08, and R09-11 values
in the development IMS. Record exact observed values, receipt IDs, tester,
time, and any defect in the checklist. A CLI exit code of zero is not a pass.

## Current machine status

On 13 October 2026, the exact R09 candidate was installed on one authorized
RMX2189 phone running Android 11. No Coolerz POS package existed before this
installation, so there was no old queue to preserve; R09-10 still needs a
separate prior-build update rehearsal. The app opened to the development
Cashier sign-in screen without a startup crash. Artemis was cloned into
`apps/android-pos/.local/artemis`, its Python environment was installed, and
`artemis doctor` found the device ready. The linked Supabase project ref was confirmed as
`fhyqrgxwdqlyzxsnpthr`, and `supabase migration list --linked` showed the
local and remote migration versions matched through `202610130002`. The user
signed in on the phone. The first R09-02 attempt stopped because the OpenAI
key returned `429 credit_balance_exhausted`; a Gemini 3.8 Flash attempt hit
project quota. With the user's Gemini key and Gemini 3.5 Flash-Lite, Artemis
completed a catalog and inventory navigation. It observed one sellable product,
Large Ice Cream Twirl at ₱25.00, and inventory of 298 large cones, 300 small
cones, 10,000 g Ube Powder, and 9,998 g Vanila Powder. The saved screenshot
confirms the inventory figures. Its report does not provide recipe quantities,
and the step log does not show a reliable cross-stall check. IMS comparison is
now complete for the visible price and stock figures: the selected Main Ice
Cream Stall shows the same quantities and ₱25 price. IMS's recipe is 1 g Vanila
Powder plus 1 Large Ice Cream Cone, estimated COGS ₱2.02. The phone UI did not
expose recipe quantities, and cross-stall isolation remains unproven, so R09-02
remains pending. R09-01 is recorded separately below.

A later read-only snapshot of the updated phone database found five active
products, all scoped to the signed-in Main Ice Cream Stall, and the Twirl
recipe as exactly one Large Ice Cream Cone plus 1g Vanila Powder. Those values
match IMS. Development IMS's system overview currently shows only one active
stall, so a foreign-stall catalog item is unavailable for a cross-stall
isolation check. R09-02 remains partial for that reason.

At about 16:58, Artemis also confirmed the signed-in home screen without a
visible JSON-null error. The tester later clarified this was the same physical
Realme phone used in the earlier manual test. Registration reuse is suggested
by the absence of a reported activation prompt and confirmed by matching the
current phone's device ID to IMS's active POS ID and the earlier server day.
R09-01 passed. At 17:00, an approved R09-03 run opened the operating day and saved one
Large Ice Cream Twirl sale, local receipt
`LOCAL-2ECB95C2-4F53-4E59-ACA5-509D942CE8D1`, for ₱25 with ₱50 tendered and
₱25 change. Sync initially failed before and after checkout with
`OPERATING_DAY_TRANSFER_REQUIRED`; at that point the sale was pending locally. A read-only
query of the debug phone's Room database found local day
`287bb0c0-6de1-42e9-9c96-9165945f6981` unsynced with that error, and the
sale unsynced with `OPERATING_DAY_MISMATCH`. The temporary database copy was
removed after inspection. Artemis was
stopped after its retry attempts did not resolve the error. The server function
`push_business_day` emits this error when a day for the same stall and date
already has a different ID. The tester confirmed IMS has an open day for this
stall from earlier manual testing on the same physical Realme phone. The app's recovery import also refuses to replace a
different local day, so re-signing in or activating again is not a safe shortcut.
The local and server day identities required reconciliation before
more sale, stock, deduction, or closing tasks. The saved checkout was not
repeated and app data was preserved. The R09-03 evidence under
`.local/r09-artemis` includes the Artemis report, 25 steps, and phone screenshot.
The runner blocks mutating cases while `.local/r09-artemis/SYNC_HOLD.json`
exists. That temporary hold was removed after the pending receipt, ingredient
movements, queue, and day identity were reconciled and verified.
The Android checkout code now also rejects a sale when its open local day has
a sync error. This guard passed its focused unit test, but the currently
installed candidate predates the change; install an updated build in place
when rehearsing R09-10, preserving the app's now-synced data.
The same read-only local query found ₱2.02 COGS, −1 g Vanila Powder and −1 Large
Ice Cream Cone for this sale. Development IMS shows the earlier operating day
opened at 2:04 AM, one different synced ₱50 receipt, and no match for the new
local receipt. The IMS Users & access screen labels the active POS “Realme”;
the tester confirmed that is the same phone used for the earlier manual run.
No POS package was present before the candidate install, so the earlier local
day record was unavailable to the new installation. The earlier queue was
reported empty. The original phone database and its pending receipt were
preserved during the approved reconciliation.
The earlier server day ID is `adf1fb4a-5bca-48ff-8fad-d3d9f2e2a211`; its
device ID is the current phone's `2504c8e5-6045-460f-912d-1ba710dce1fa`.
Before repair, IMS showed ₱50 gross sales, one order, ₱4.04 COGS, no deductions
or waste for that day. The local day had a different ID. This was a same-device
day-identity conflict following an app reinstall.

The development IMS operating-day and device screens now display the relevant
UUIDs for administrators. If those screens are unavailable, a development
project administrator can collect them with this **read-only** query in the
Supabase SQL Editor for project `fhyqrgxwdqlyzxsnpthr`:

```sql
select bd.id as server_day_id, bd.device_id as server_device_id,
       d.device_name, d.is_active as device_is_active,
       bd.opened_at, bd.closed_at
from public.business_days bd
join public.stalls s on s.id = bd.stall_id
left join public.devices d on d.id = bd.device_id
where s.code = 'MAIN-001'
  and bd.business_date = date '2026-10-13'
  and bd.deleted_at is null;
```

For similar future incidents, compare `server_device_id` with the phone's local
device ID and review every pending receipt and ingredient movement before any
day-identity repair. A matching device ID alone does not justify a repair.

## R09-03 reconciliation result

With explicit tester approval, the stopped phone's database and WAL were
backed up, integrity checked, and patched on a separate copy. The validated
copy linked the one local day, one sale, and two pending ingredient movements
to the existing server day, with the earlier ₱50 sale/one order/₱4.04 COGS as
server-known baseline. After the tester signed in locally, the app uploaded
the pending receipt automatically. IMS shows the new receipt exactly once,
two total orders, ₱75.00 gross, ₱6.06 COGS, 297 Large Ice Cream Cones, and
9,997 g Vanila Powder. The POS home shows ₱75.00 and two orders; a post-sync
database snapshot shows the day, sale, and both ingredient movements synced
with no errors. The live data is reconciled, but R09-03 remains blocked as a
regression test because the installed app did not resume the existing server
day automatically. A day-resume fix and clean retest are required. The original
database and WAL remain backed up. See [the reconciliation record](R09_DAY_RECONCILIATION.md).

## Development same-device day-resume fix

The source fix calls `get_resumable_pos_day` at Cashier sign-in. For a newly
installed app on the same active device, the RPC returns the existing open day
and its server-known totals; an existing local day is left intact to avoid
double-counting local receipts. The new migration is
`supabase/migrations/202610130003_resume_active_pos_day.sql`. On 13 October,
`supabase/.temp/project-ref` was `fhyqrgxwdqlyzxsnpthr`, the linked remote
migration history matched through `202610130002`, and a dry run showed exactly
this one migration pending, with no seeds or roles.
The focused Android session, HTTP contract, and checkout tests passed. A
`devDebug` APK built successfully at
`apps/android-pos/app/build/outputs/apk/dev/debug/app-dev-debug.apk` with
SHA-256 `4ED1C0D7E0EFE2120218F0ABAA22217D8E33D57A239AE565B50063FD925B2367`.
The original R09 candidate and its evidence remain separate.
The updated build is frozen under `apps/android-pos/app/build/r09-updated/`
with its own manifest. This read-only preflight passed against the installed
phone:

```powershell
node .\apps\android-pos\scripts\r09-artemis.mjs verify --manifest .\apps\android-pos\app\build\r09-updated\manifest.json --apk .\apps\android-pos\app\build\r09-updated\coolerz-pos-dev-r09-day-resume-20261013.apk
```

Use the same `--manifest` and `--apk` arguments on future `run` commands for
the updated build. Without them, the runner correctly rejects the installed
phone because it differs from the original candidate.

After explicit approval, the migration was pushed to the linked development
project and `migration list --linked` confirmed local and remote version
`202610130003`. The updated APK was installed with `adb install -r` on the
same RMX2189 phone; its signing certificate SHA-256 matched the original
candidate (`7e7a6102a80ae197eafe3cd38eabb8ccd4d1dfadacba003ac42151c68e450830`).
A post-update stopped-app database snapshot under
`apps/android-pos/.local/r09-artemis/reconciliation-20261013T102047Z/`
passed SQLite integrity checks. It retains server day
`adf1fb4a-5bca-48ff-8fad-d3d9f2e2a211`, the one synced Artemis receipt,
297 large cones, 9,997 g Vanila Powder, and zero pending ingredient rows.
The app opened at its Cashier sign-in screen. The tester signed in on the
phone, and a read-only UI dump confirmed the existing day still opens at
2:04 AM with ₱75.00 sales and two orders. This verifies the update preserved
the existing local day; it does not exercise a fresh install with no local day.

These are the repository-root PowerShell commands to inspect the linked project,
preview the pending migration, apply it, and verify remote history:

```powershell
Get-Content .\supabase\.temp\project-ref
& .\node_modules\.bin\supabase.cmd migration list --linked
& .\node_modules\.bin\supabase.cmd db push --dry-run
& .\node_modules\.bin\supabase.cmd db push
& .\node_modules\.bin\supabase.cmd migration list --linked
```

The dry run is a preview, not an application. Do not run the SQL test fixture
against the linked project. Deploy the server RPC before installing a client
that calls it. R09-03 needs a clean same-device fresh-install retest before
its regression verdict can change.

## Later phone cases on the updated build

The updated-build runner preflight checks the installed APK's SHA-256 against
the frozen `r09-updated` APK. Artemis R09-04 recorded one ₱10.00
profit-affecting `R09 test expense` deduction. Phone expected cash fell from
₱75.00 to ₱65.00. IMS shows the same deduction once, and a phone snapshot
shows its row synced with no queue error. R09-04 passed review.

Artemis R09-07 disabled Wi-Fi and mobile data, saved exactly two offline
₱25.00 Large Ice Cream Twirl receipts with ₱50.00 tender each, and force-stopped
and relaunched the dev app without clearing data. Its Gemini run briefly hit
a free-tier token quota and retried, but finished successfully; independent
SQLite inspection after relaunch showed both receipts and four ingredient
rows pending, plus 295 large cones and 9,995 g Vanila Powder. The app required
online sign-in after relaunch. The tester signed in after Wi-Fi was restored;
POS History then showed both receipts and Inventory showed the expected stock.
R09-07 passed review. Reauthentication automatically synced the two receipts,
so R09-08's reconnect, acknowledgment, and uniqueness checks passed without
the planned interrupted-upload retry. Phone SQLite has zero pending sale or
ingredient rows; IMS has each new receipt once, four total receipts, ₱125.00
gross, ₱10.10 COGS, and the matching stock. R09-08 remains partial until a
controlled interrupted upload can be tested with a different pending record.
Do not replay either acknowledged receipt.

R09-05 and R09-06 initially needed test-data setup because both powders were
untracked. With tester approval, Ube Powder was configured at 10,000g total, 10 sealed
packs, and 0g hopper. Artemis R09-05 received one 1,000g pack; phone and IMS
showed 11,000g/11 sealed/0g hopper and one synced receive row. Artemis R09-06
opened one pack; phone and IMS showed 11,000g/10 sealed/1,000g hopper and one
synced zero-gram, zero-cost opening row. Both queues were empty and both cases
passed review.

For the separate R09-10 preservation rehearsal, `R09-10P` prepares exactly
one pending offline sale on a known prior build. Freeze and verify that build's
APK, record the pending receipt, install a compatible newer APK in place while
offline, and run `R09-10` against the newer build's manifest to inspect the
queue before reconnecting. The runner does not install either APK. The next
compatible `devDebug` APK is prepared under
`apps/android-pos/app/build/r09-update-rehearsal/` with SHA-256
`B13FFF89360E0E781CCDAC4E03CDACFEBD39D31E8EBE2BD1FA9C486AC4EF347F`;
its signing certificate matches the prior build. The approved R09-10P run
saved one offline receipt,
`LOCAL-758CD3B0-0945-460D-9E33-FFC54C0CD76B`, at ₱25.00 with ₱50.00
tender. Before updating, a verified phone database backup showed that receipt
and its two ingredient movements as the only pending rows, linked to the
existing open day, with 294 large cones and 9,994g Vanila Powder. Both radios
were off. The newer APK was installed in place with `adb install -r`; the
runner verified the installed APK hash as `B13FFF...347F`. A second backup
while offline had the identical archive hash and the same pending queue, day,
and stock. Artemis R09-10 opened the updated app offline and reached sign-in
without creating or syncing another record. Wi-Fi was restored after this
inspection. After Wi-Fi returned and the tester signed in as Cashier, a
read-only phone snapshot showed the receipt and both ingredient movements
synced with no error and an empty queue. IMS Transactions showed that exact
receipt once, with ₱25.00 total, ₱50.00 cash received, and ₱25.00 change.
The existing day showed five receipts, ₱150.00 gross, ₱12.12 COGS, one ₱10.00
profit-affecting deduction, 294 large cones, and 9,994g Vanila Powder. The
Ube setup remained 11,000g total, 10 sealed packs, and 1,000g hopper. R09-10
passed review; the evidence snapshots are under ignored
`apps/android-pos/.local/r09-artemis/`.

After R09-10, the tester declined the clean app-data reset and extra sale for
R09-03, and the extra offline sale for the controlled upload interruption in
R09-08. Those specific checks remain unrun. The `R09-03R` runner case is a
guarded plan for a future approved clean retest; it stops before checkout if
the existing 2:04 AM day is not resumed.

The tester approved R09-11 with ₱140.00 counted cash. Artemis closed the
existing development day exactly once, but its local closing row remains
pending with `CLOSING_RECONCILIATION_MISMATCH`. The phone resumed a day with
one server-only sale, so amount baselines agree while closing row counts do
not. A guarded fix, migration preview, and compatible APK are prepared in
[R09_CLOSING_RECONCILIATION.md](R09_CLOSING_RECONCILIATION.md). No closing retry
has been attempted.
