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
- If a sale saved before close uploads after its closing was posted, IMS corrects that day and flags it under **Transaction history → Sales uploaded after closing**. The Owner compares the receipt, expected cash and counted cash, records the variance, and signs off only after the queue is empty.

## Refund and cash correction

1. Open the current operating day on the activated POS. Find the original receipt in **History**, open it, and choose **Refund or void this receipt**. Enter a specific reason and choose **Restock** only when the ingredients or stock can physically return to inventory; otherwise choose **Waste**.
2. Choose **Refund** when cash is handed back to the customer. V1 returns the full receipt amount; confirm that amount with the customer. Choose **Unpaid void** only for an entry made by mistake when no cash is returned. The Cashier may do either without prior approval under the accepted v1 scope.
3. Keep the POS installed until the correction shows **Synced to IMS**. A queued reversal is saved locally, can replay safely, and blocks the day's final closing until it reaches the server. Never create a generic cash deduction or use IMS stock adjustment as a refund substitute.
4. The Owner reviews **Transaction history → Refund and void audit**, including receipt, reason, Cashier, stock action, cash returned, original day and payout day. A correction after close updates the original day's revenue and profit; that day's counted cash remains the historical closing count. The cash payout reduces the current day's expected till cash. If payouts exceed current cash intake, count the actual cash, document how the payout was funded in closing notes, and have the Owner review the variance.

This procedure is code-tested in R06. The physical-phone exercise, live development backend and final Owner/Cashier UAT remain later release gates; do not begin real-sales UAT until those pass.
