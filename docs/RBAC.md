# Roles, stall access, and operating days

The system has three application roles. The current installation can run one active stall, while the data model and dashboard support assigning an Owner to several stalls later.

| Capability | System admin | Owner | Cashier |
|---|---:|---:|---:|
| Use the web IMS | Yes | Yes, assigned stalls | No |
| Use the Android POS | No | No | Yes, assigned stall and activated device |
| View revenue and sales reports | Every stall | Assigned stalls | Current-day local totals only |
| View costs, overhead, and profit | Every stall | Assigned stalls | No |
| Manage products, prices, and inventory | Every stall | Assigned stalls | No |
| Manage stall name and code | Every stall | No | No |
| Manage daily costs and overhead | Every stall | Assigned stalls | No |
| Create stalls | Yes | No | No |
| Create or update Owners | Yes | No | No |
| Assign an Owner to several stalls | Yes | No | No |
| Create or update Cashiers | Yes | Assigned stalls | No |
| Generate a POS activation code | Yes | Assigned stalls | No |
| Open and close an operating day | No | View history | Yes |
| Create sales | No | No | Only while the operating day is open |

System admin access is global. Owner access comes from `owner_stall_access`, and a stall selector appears in the web header when an Owner has more than one assignment. A Cashier remains assigned to one primary stall.

The web IMS presents separate role experiences. A System Administrator lands on **System overview** and receives an **Administration** navigation group with **Stall administration** and **Users & access**. An Owner lands on the operational dashboard and sees **Costs & settings** plus **Cashiers & POS** for assigned stalls. The System Administrator can still open selected-stall operational screens for support, but the database remains authoritative: exposing or changing a client-side menu cannot grant an Owner global access.

## Owner access on iPhone

The Owner uses the responsive web IMS as an iPhone home-screen app. This keeps inventory, staff, expenses, and reports in the existing React application and avoids maintaining a second native codebase.

After the web IMS is deployed over HTTPS, open it in Safari, tap **Share**, choose **Add to Home Screen**, and launch **Coolerz IMS** from the new icon. The manifest and iOS metadata are already included. The service worker deliberately does not cache business or financial data; an internet connection is required for the Owner dashboard.

## Staff and POS setup

1. The System admin creates the active stall, then creates an Owner and assigns that stall.
2. The Owner opens **Staff & devices** and creates the Cashier account.
3. The Owner generates a one-time activation code for the stall's Android POS. Redeeming a replacement code deactivates the previous POS for that stall.
4. The Cashier signs in on Android and redeems the code while online. Later checkout and operating-day actions save to Room first and can work offline.

## Operating-day rules

The Cashier must open the stall before starting a sale. Opening records the Manila business date, Cashier, device, exact opening timestamp, and optional notes in Room, then queues the record for sync. Only one operating day can be opened per stall and business date.

Closing records the exact closing timestamp, the completed sales total for that stall between opening and closing, and optional notes. A closed business date cannot be reopened from the POS. The Owner sees both timestamps and the closing total in **Operating days** on the web dashboard.

Operating-day records sync before queued sales. The cloud sale function accepts a sale only from the activated Cashier device and only when its timestamp falls inside that device's synced opening and closing window.

## Database bootstrap

Apply all migrations in filename order. The previous `manager` enum value is renamed to `owner`. On a fresh database, run `create_initial_system_admin` as the database owner. On an existing database, run `promote_user_to_system_admin` for one chosen Owner. See [the Supabase setup guide](../supabase/README.md) for the exact sequence.
