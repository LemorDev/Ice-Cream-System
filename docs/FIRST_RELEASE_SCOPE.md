# First-release scope and operating rules

Status: **Owner and Cashier acceptance reported for scope revision `0bba1be`**, 5 October 2026. This defines the intended one-stall release; it does not certify that the current software passes these rules. Use [R05–R12](FIRST_RELEASE_TASKS.md) to implement and test them before real sales.

## Release boundary

One stall uses one active, signed Android POS phone. The Cashier records sales and stock receipts on that phone. The System administrator maintains catalog, recipes, opening stock, corrections, staff, and device access in the IMS. The Owner uses the read-only dashboard at the same HTTPS web address as the IMS. All amounts are Philippine pesos; the operating timezone is **Asia/Manila**. Real transactions belong only in production after the release gates pass.

| Decision | Scope | Operating rule and acceptance check |
| --- | --- | --- |
| Products, recipes, units, and opening stock | **In v1** | Before the pilot, the Owner approves the actual menu, prices, ingredient list, base unit for each stock item (g, ml, or piece), pack-to-base conversion, recipe quantity per sale, unit cost, and physical opening count. Every prepared sellable item has a complete recipe covering ingredients and packaging. A standalone prepackaged item may track its own sellable stock only if it is listed as an approved exception. In UAT, selling two units consumes twice each recipe quantity, a received pack adds the converted base quantity, and POS and IMS show the same remaining stock and cost. No item with missing or ambiguous conversion goes live. |
| Completed-sale refunds, voids, and mistaken sales | **In v1; release blocker until implemented and tested** | The Cashier can find the original receipt in the POS, reverse a whole sale with a required reason, choose restock or waste for returned goods, and record the actual cash returned. A correction to only part of a sale uses a linked full reversal and a new sale for the retained items. Repeating or syncing the action must not reverse stock, revenue, or cash twice. The POS, IMS, closing, and Owner report must all show the same result, including when the original sale was offline, already synced, or in a day already closed. The Cashier may reverse a closed-day sale without prior approval. That refund **adjusts the original operating day's reports** with an audited correction linked to the original receipt; it also records when and from which till the cash was actually returned so the current till reconciles. The Owner can review the correction and reason in the dashboard. Until this passes, do not use a cash deduction or an IMS-only reversal as a substitute for the POS flow. |
| Operating day across midnight | **In v1; release blocker until implemented and tested** | One day starts when the Cashier opens it and ends at the deliberate close, even if midnight passes. Its business date is the **Asia/Manila date when opened**; no automatic split occurs at midnight. Only one day can be open for the stall. Sales, receipts, deductions, voids, stock movements, close totals, and Owner reports are assigned to that same operating day by day ID rather than the calendar date of each timestamp. After closing, the next day may open. UAT opens before midnight, sells before and after midnight, then closes and verifies one coherent day across POS and IMS. |
| Cash deductions and profit deductions | **In v1** | A cash deduction records money actually removed from the till, with amount, reason, cashier, timestamp, and supporting voucher. It reduces expected closing cash. Mark **affects profit** only for a new business expense that is not already included as fixed overhead or another expense; a transfer of cash or payment of already-accounted overhead reduces till cash only. A refund follows the reversal workflow above, not a generic deduction. In UAT, gross ₱200 with a ₱15 new expense and ₱10 transfer produces expected cash ₱175 before starting float and other cash movements; only ₱15 reduces profit as a deduction. Owner and Cashier verify the same figures in the POS, IMS, and Owner report. |
| Stock receiving, waste, and corrections | **In v1** | The Cashier receives raw ingredients and packaging with a base-unit quantity and delivery reference in the notes; the receipt survives offline use and syncs once. Pack conversion is set in the IMS catalog and shown during setup. The System administrator makes physical-count corrections and records waste with a reason and audit trail; the Cashier reports a discrepancy or damage and does not quietly overwrite stock. UAT counts a selected ingredient and package before and after a receipt, sale, waste entry, and correction, and reconciles the ledger to the physical count. Corrections must not rewrite historical sale quantities or costs. |
| Internet unavailable while the POS still works | **In v1** | An already activated and signed-in POS continues to record numbered sales locally. The Cashier keeps the phone and app installed, checks the pending queue, and reconnects before the final day sign-off. Each queued operation reaches the IMS once; the Owner treats cloud totals as provisional until the queue is zero and the day is reconciled. If login/activation is required and unavailable, use the full POS-unavailable procedure below. |
| POS phone/app unavailable | **Deferred with procedure** | Sales may continue with a pre-numbered paper receipt and incident log for each sale: time, item/quantity, price, cash received/change, receipt number, cashier, and any return or stock issue. Keep the cash and physical stock; do not uninstall/reset the phone or use a replacement device until the administrator checks the original pending queue. Contact the named pilot support person immediately. The System administrator and Cashier reconcile paper receipts, till cash, stock, and any phone-only queue before entering missing records through an approved recovery path; never blindly re-enter a sale that may sync later. The Owner signs the reconciliation before normal POS use resumes. R18 supplies the printable sheet, support contact, and stop conditions. |

## Current implementation gaps to resolve in R05–R12

- The IMS has an administrator sale reversal with restock/waste choice. The current Android POS has no equivalent reversal action or observed pull of a changed sale status, so its local close may still count an IMS-voided sale. The agreed v1 flow requires client, sync, database, and report work plus UAT.
- The Android day is labelled with its opening date and can remain open across midnight, but date-based cloud reports and close calculations need to be checked and aligned to the operating-day ID. The midnight case is a release blocker until it reconciles end to end.
- Existing local tests do not prove live database behavior, physical-phone recovery, or the cross-client totals above. R08–R12 provide that evidence.

## Menu and unit sheet for production setup

The Owner and Cashier accept the unit and recipe rule in R02. The actual catalog is filled and checked in R17/R21 before the pilot. Use a linked catalog export if the list is long; record its file name and revision here. Recipe quantity is in each ingredient's base unit, and opening count is a physical measurement taken before the production pilot.

| Item or approved catalog reference | Type | Base unit | Pack size and conversion | Recipe per sale / exception | Sale price or unit cost | Opening count | Owner/Cashier checked |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pending actual menu and stock list | Pending | Pending | Pending | Pending | Pending | Pending | Pending |

## Sign-off and change control

The Owner and Cashier acceptance of scope revision `0bba1be` was reported by the user in this task on 5 October 2026; individual names were not provided. The System administrator prepares the actual catalog and the Owner approves it in R17/R21. The Developer prepares the paper sheet and the Cashier reviews it in R18. Scope acceptance confirms intended behavior; later UAT signs off on the tested implementation and figures.

| Role | Name | Accepted revision/commit | Date | Status |
| --- | --- | --- | --- | --- |
| Owner | Not provided | `0bba1be` | 2026-10-05 | **Accepted, as reported by user** |
| Cashier | Not provided | `0bba1be` | 2026-10-05 | **Accepted, as reported by user** |
| Developer (scope recorded) | Codex | `0bba1be` | 2026-10-05 | **Recorded** |

Follow-through after scope acceptance:

1. Confirm the original-day correction and current-till cash record in UAT, including a Cashier action without prior approval.
2. Name the support person and agree on specific stop conditions for paper sales in R18.
