# Cashier and Owner operating instructions for the first release

These instructions apply to the one-stall pilot only after the R06 checklist and R08–R09 tests pass. The Owner and Cashier should keep a printed copy with the numbered paper receipt book.

## Account recovery

1. The Cashier or Owner reports a lost password or suspected account compromise to the System administrator using the agreed support contact. The System administrator verifies the person through the pilot staff roster and a call to the registered contact; do not accept only an email or a request from an unfamiliar phone.
2. The System administrator uses **Users & access** for the correct stall and user, sets a strong replacement password, and records the support incident. The server revokes that user's existing web/POS sessions and clears the login cooldown when the password changes. Deactivate the account instead if identity cannot be verified.
3. Communicate the new password directly to the verified person, not in a shared chat or paper receipt. The person signs in again. There is no self-service password change in v1; any later change goes through the System administrator. A POS with unsynced sales must stay installed and on the same stall; sign in again on that phone to sync the queue.
4. The administrator checks the security audit action `user.credentials_reset` and confirms old tokens fail. If the reset or re-sign-in cannot be verified, keep the account inactive and continue on numbered paper until support resolves it.

## Offline POS and replacement

- If the activated POS can still record sales, continue in the app and watch the pending queue. Treat Owner cloud totals as provisional until the queue is zero and the operating day reconciles.
- If the phone/app cannot record a sale, issue the next pre-numbered paper receipt. Record time, items and quantities, price, cash received and change, cashier, and any stock or return issue. Keep the receipt book and cash together. Contact the named support person immediately; **name and number must be filled in by R18 before the pilot**.
- Never uninstall, reset, clear app data, or switch the phone to another stall while sales remain on it. A lost phone's unsynced sales **cannot be recovered from Supabase**. The System administrator compares the server-known sales, the phone queue if available, the paper book, till cash, and physical stock. The Owner signs the reconciliation before a replacement POS resumes normal sales. Do not re-enter a paper sale that might still sync from the original phone.
- Keep a closed day provisional in the Owner report until every queued sale, stock movement, deduction and reversal has reached the server and the closing matches the local record. An unresolved rejected sale means the day is not signed off.

## Refund and cash correction

The agreed v1 procedure is a full POS reversal linked to the receipt, with a reason, restock or waste choice, and actual cash returned. A correction after close changes the **original** operating day's report and separately records the payout in the current till. The Cashier may perform it without prior approval, and the Owner reviews the audit record. This flow is still a release blocker in [R06](R06_BLOCKER_CHECKLIST.md); until it passes, **do not conduct real-sales UAT or use an IMS stock reversal or generic cash deduction as a refund substitute**.
