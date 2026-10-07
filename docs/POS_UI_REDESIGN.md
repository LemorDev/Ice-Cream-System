# Coolerz POS UI redesign

## Inspection and implementation plan

The mobile client is native Kotlin / Jetpack Compose / Material 3. A single Hilt `PosViewModel` owns cashier session, cart, search/category, receipt and operation state. Screens collect repository StateFlows with lifecycle awareness. Compose Navigation currently routes login → activation → Home, with Home links to catalog, inventory, history and settings. Cart/Payment and Pos/Transactions files also exist, but are not active routes; the actual checkout uses the catalog's order/payment bottom sheet and review confirmation.

Room v6 holds sessions, products, recipes, transactions/items, inventory ledger, business days, deductions, closings and sync cursors. Login uses stall code/email/password; server device activation and persisted authorization stay unchanged. Checkout checks cashier/device/open-day authorization, calculates ingredient usage across the order, and atomically stores the sale/items/ledger and updates stock. Shared ingredient reservations determine catalog availability. Receiving only permits raw ingredients/packaging for the cashier's stall and commits stock plus ledger together.

Synchronization uploads operating days before sales, inventory movements, deductions and closings, then pulls catalog/recipes/ledger with existing cursors and duplicate protection. WorkManager schedules network-constrained immediate and 15-minute periodic work. Manual sync and catalog-entry refresh also exist. Checkout requires no network. Startup's existing session expiry policy remains unchanged. Business dates use Asia/Manila; closing is final for that date, requires the opening device, and records expected/count cash separately. Deductions retain their reason and revenue cap. The visibility icon toggles amounts across dashboard/history/receipts.

The workspace already contains substantial unfinished POS/IMS/database changes. This redesign builds on that state and does not replace those changes. Backend schemas, API contracts, payloads, stock calculations and sync scheduling require no changes.

1. Add Home / Sell / Inventory / History / More bottom navigation with saved tab state and an uninterrupted shared cart. Preserve authentication/activation routing; keep checkout and receipt navigation out of tab switching.
2. Replace Home's feature collection with cashier identity, compact stall/date status, a strongest New Sale action, sales/orders and subtle sync. Move existing day/deduction controls into More's Operations destinations.
3. Reuse the catalog workflow/keypad; use cashier-friendly availability labels, visible change, horizontal categories and a compact layout for small displays.
4. Make Inventory a searchable stock list with natural quantities, existing low-stock rules and explicit receiving. Make History compact tappable rows and share readable receipt rendering for new/historical sales.
5. Group More into Operations / Account / System, separate device information, retain sign-out and closing confirmation, and refine spacing/type/touch targets.
6. Run Android unit tests, debug builds and lint. Check navigation/insets/small-screen layout; record device/live-service limitations explicitly.

## Risks to verify

- Tab restoration must preserve the cart/search and avoid duplicate destinations or old authenticated screens after sign-out.
- Sell remains gated by the existing open-day rule, including direct tab entry.
- A committed sale must never be reported as failed merely because receipt detail presentation could not load.
- Sync presentation must distinguish actual network availability from retry/failure state without changing scheduling.
- Receipt time formatting is presentation-only; stored UTC timestamps stay untouched.
- Inventory receiving must preserve validation, confirmation, atomic persistence and automatic upload.

## Implementation

Five labeled bottom destinations now share the existing ViewModel and restore tab state. Receipt screens sit outside the tab bar; More groups operations, account and system tools. Authentication/activation selects the correct starting screen, and a session boundary discards old navigation state. Sign-out also clears the in-progress cart/search/receipt UI while leaving stored records alone.

Home prioritizes New Sale and day sales/order counts; detailed opening/closing and deductions moved to Operations. The money visibility control sits by the amounts it affects. Sell retains category/search, ingredient reservations, cart steppers, the custom cash keypad, Exact Amount, review and receipt. Direct Sell entry respects the open-day rule. Only the payment product list scrolls; totals, cash entry, keypad and review stay fixed. Inventory retains receiving validation/confirmation and displays fractional balances naturally; History uses compact tappable rows. New and historical receipts share original persisted items, money and business-timezone formatting.

Sync now has separate UI progress from local cashier actions. A network callback supplies the offline presentation; existing repository sync and WorkManager scheduling are unchanged. A receipt detail read happens after checkout commits and cannot report an already saved sale as failed. No repository, API, payload, database or backend changes were made by this redesign.

## Verification

- Final checks passed: `:app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleProductionDebug :app:lintDevDebug` (76 tests, zero failures/errors, zero lint errors). Lint retains 52 existing dependency/version/icon/device warnings; the existing AGP/SDK compatibility warning is unchanged. Both APKs are debug-signed, including the production configuration flavor. Existing regression coverage includes session/device authorization, local checkout, ingredient availability, operating-day rules, receiving, sync retry/idempotency and worker behavior. New coverage checks quantity/time presentation, connection-aware sync labels, local checkout during sync and receipt-read failure after a committed sale.
- Interactive review ran on an isolated 480 × 800 px / 240 dpi emulator (320 dp wide). The installed image is Android 16 / API 36; this verifies a small display comparable to older Android phones, not an Android 11 runtime.
- Login layout, five tabs, category filtering, quantity increase/decrease and cart retention across tabs were exercised. Exact Amount showed zero change immediately; custom keypad entry of ₱50 against a ₱40 total showed ₱10 change. The sale completed with networking disabled and opened a full persisted receipt. History showed the new pending sale and reopened its receipt with readable local time and correct totals.
- A sample 10 g delivery changed local stock from 598 g to 608 g offline. A ₱5 deduction against ₱80 sales produced ₱75 expected cash. Closing required final confirmation, recorded the closing, and disabled sales for that day. Settings, Device Information and Sync Status were opened. Sign-out returned to Login; Android Back/relaunch did not restore authenticated navigation.
- A read of the emulator's actual Room database after sign-out confirmed zero session rows, two sales totaling ₱80 (one pending), stock of 608 g / 19 standalone portions, a pending +10 g receive ledger row, a pending ₱5 deduction and a pending ₱75 closing. All data was synthetic and networking stayed disabled; no live IMS record or real account was changed.
- The small-display review prompted tighter Home spacing so the sync line fits when open, more compact product rows, readable status-bar icons, clearer accessibility labels and disabling quantity controls while a sale saves.

Live server sign-in/activation, reconnection to a real IMS, Android 11 runtime behavior and signed release deployment still need acceptance testing. Repository/worker tests verify sync behavior using mocked services; the offline emulator review does not claim live server integration.

## Small-display previews

These screenshots use synthetic local data. The five primary screens were captured from the final development APK; payment is from the offline workflow review.

- [Home](pos-ui-review/home.png)
- [Sell](pos-ui-review/sell.png)
- [Inventory](pos-ui-review/inventory.png)
- [History](pos-ui-review/history.png)
- [More](pos-ui-review/more.png)
- [Cash payment and change](pos-ui-review/payment.png)

Build outputs: `apps/android-pos/app/build/outputs/apk/dev/debug/app-dev-debug.apk` and `apps/android-pos/app/build/outputs/apk/production/debug/app-production-debug.apk`.

## Logo, serving filters and fixed payment follow-up

The existing `Coolerz icecream logo.png` is bundled as a local drawable and shared by Login, Home and Loading. It does not require connectivity.

Sell includes All, Cup and Cone before the existing categories. Cup/Cone selects sellable, active menus whose recipe packaging contains the corresponding serving word. Recipe packaging takes precedence; menus without identified serving packaging fall back to name, category or unit. Whole words and plurals are supported, so “Cupcake” and “scone” do not count as cup/cone. Search still applies. Ordinary categories and serving chips select one filter at a time; All clears both. Availability, reservations and checkout calculations are unchanged.

The payment sheet has a weighted LazyColumn containing only cart products. Its header, total/change, cash received, Exact amount, four keypad rows and Review sale button stay outside that list. Keypad and quantity controls retain 48 dp touch targets.

Follow-up unit checks: 81 tests pass, including five new recipe/serving/search/category tests. Development lint reports zero errors and the same 52 existing warnings.

Both development and production-debug APKs were rebuilt successfully. Follow-up UI review used the same 320 dp wide API 36 emulator configuration with networking disabled and fresh synthetic data. Cup displayed six cup recipes; Cone displayed six cone recipes, including menus with neutral names such as “Flavor 01”. A twelve-product cart scrolled from Flavor 01 through Flavor 12. Accessibility bounds for all twelve keypad keys, Exact amount and Review sale were unchanged after scrolling. Exact amount on a ₱480 order showed ₱0 change; custom keypad entry of ₱500 showed ₱20 change. Login and Home rendered the bundled logo.

- [Login with logo](pos-ui-review/login-logo.png)
- [Home with logo](pos-ui-review/home-logo.png)
- [Cone recipe filter](pos-ui-review/cone-filter.png)
- [Fixed keypad with twelve products](pos-ui-review/payment-fixed.png)
- [Last product reached with keypad visible](pos-ui-review/payment-fixed-scrolled.png)

The isolated emulator and synthetic database were removed after verification. No live account or IMS data was used.

## Home analytics and system-header follow-up

The activity's content now draws edge-to-edge with safe drawing insets applied once at the app root. This keeps Home and the other POS screens below phone status bars and above navigation controls, including devices that enforce edge-to-edge layouts. The Home brand remains left aligned. Long cashier names can wrap to two lines. Today's Sales and Orders use bordered cards.

Home has a selectable 7-day or 30-day chart of completed, non-deleted sales for the current stall. The 30-day view groups five business days per bar. Purple is revenue and teal is completed order count; each series uses its own scale, noted in the chart. Tap a bar group to inspect its date range and totals. The existing hide-amounts control hides revenue bars and values but leaves order counts visible. Business dates use the Manila time zone and local persisted transactions; checkout and sync logic are unchanged.

The owner, system administrator and report charts in the IMS now show revenue on the left axis and completed orders on the right. A visible legend, hover/tap tooltip and day picker explain the series and let users inspect exact daily values. Hidden financial amounts remove the revenue line. The IMS build, lint and 40 tests pass. Android verification passes with 83 unit tests, both debug APK configurations and zero lint errors. An isolated 320 dp-wide API 36 emulator showed correct status-bar spacing, readable long cashier name, bordered cards, chart selection and amount privacy. All emulator data was synthetic and offline. The original user's physical phone still needs installation for OEM-specific acceptance.

- [Home header and metric cards](pos-ui-review/home-analytics-header.png)
- [Interactive chart](pos-ui-review/home-analytics-chart.png)
- [30-day chart with revenue hidden](pos-ui-review/home-analytics-private.png)
