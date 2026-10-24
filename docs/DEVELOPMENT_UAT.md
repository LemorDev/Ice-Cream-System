# Combined IMS/POS development acceptance draft

Use the DEVELOPMENT DATABASE web build and `com.icecreampost.pos.dev` only.
Record the candidate commit, HTTPS URL, APK hash/certificate, named tester,
date/time, receipt IDs, actual amounts, and result for every scenario.
Software tests do not replace Owner/Cashier sign-off or real-phone evidence.

## Finish the existing recovery check first

Preserve the Realme's app data. Inspect the existing closing ID
`491a7c3b-5c88-4b37-81e7-0397d759d6f5` and its queue acknowledgment.
Expected original-day totals: five receipts, ₱150.00 gross, ₱12.12 COGS,
₱10.00 deduction, and ₱140.00 expected/count cash. Verify the closing once
in IMS and the local acknowledgment. Do not add a sale or reopen that day to
complete this check. See `R09_CLOSING_RECONCILIATION.md`.

## New isolated development scenario

Prepare a separate test stall/day through the normal administrator UI. Do not
execute SQL fixture files against Supabase. Record the actual starting values
before using the following expected figures:

- No previous receipts or deductions; opening cash and overhead are zero.
- Powder: 1,000 g in one sealed pack; purchase cost ₱185 per 1,000 g pack.
- Cones: 100 pieces; purchase cost ₱200 per 100-piece pack.
- Menu item: ₱40.00, recipe 45 g powder and one cone, with a test photo.
- One serving books ₱8.33 powder plus ₱2.00 cone = ₱10.33 COGS.
  Make two separate one-serving sales to keep per-sale rounding explicit.

| Scenario | Expected outcome | Actual / tester / receipt / time |
| --- | --- | --- |
| Admin and Owner web access | Admin manages the test stall. Assigned Owner sees monitoring only; Cashier has no web dashboard. Test on the intended phone browser. | Pending |
| Photo add/replace/remove | IMS and POS show each change after sync; recipes, costs, powder tracking, and quantity remain intact. An image upload failure prevents a misleading save. | Pending |
| Stall isolation | A second stall's product, stock, and photo do not appear in the activated POS catalog. | Pending |
| Open confirmation | Cancel leaves the day closed. Confirm opens one day once. | Pending |
| Online sale | One ₱40 sale, ₱50 tender, ₱10 change; one receipt in IMS; ₱10.33 COGS. | Pending |
| Powder pack opening | Opening one pack yields zero sealed packs and a hopper equal to remaining total grams; no stock or COGS change from opening itself. | Pending |
| Offline sale/restart/reconnect | One additional separate ₱40 sale survives restart; upload interruption/retry produces exactly one receipt and clears its acknowledgment queue. | Pending |
| Deductions | One ₱10 additional-expense deduction lowers expected cash and profit once. | Pending |
| Close confirmation and totals | Cancel preserves open day. Review/confirm one close: two receipts, ₱80 gross, ₱20.66 COGS, ₱10 expense, ₱49.34 profit with zero overhead, ₱70 expected/count cash. | Pending |
| Ending stock | Powder 910 g and cones 98 pieces. Sale and ingredient queues are empty after acknowledgment. | Pending |
| Biometric unlock | Enrollment, valid unlock, cancellation, background lock, restart, password fallback, changed enrollment, account switch, and sign-out behave correctly. Expired sessions require online sign-in; queued data survives. | Pending |
| In-place combined update | Same signing certificate; existing queued sale, stock, day, and closing survive version-15 migration and upload once. | Pending |

If baseline or rounding differs, record that before execution and recompute the
expected figures. Do not mark a mismatch passed by changing expected values
afterward. Complete the outstanding R09 cases separately, then record Owner
and Cashier acceptance for one exact candidate. Production remains a later
release gate.
