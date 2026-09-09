# Coolerz Android POS API and sync contract

Supabase PostgREST is the API gateway. The Android app uses the Supabase anon key, a custom application session, and a stable Android device identifier.

## Headers

Every request sends:

```http
apikey: <SUPABASE_ANON_KEY>
Authorization: Bearer <SUPABASE_ANON_KEY>
Accept-Profile: public
X-Session-Token: <custom session token>
X-Device-Id: <stable Android device identifier>
```

The service-role key is never shipped to Android. `login_pos_with_password` verifies the stall code and Cashier credentials, then returns a short-lived token. If `X-Device-Id` already matches the stall's active POS, the response also restores its cloud `device_id` so another activation code is not required. Device-protected RPCs require the same active-device match.

## Endpoints

| Purpose | Method | Path |
|---|---:|---|
| Cashier login and device restore | POST | `/rest/v1/rpc/login_pos_with_password` |
| Redeem the one-time POS code | POST | `/rest/v1/rpc/activate_pos_device` |
| Push an opening or closing record | POST | `/rest/v1/rpc/push_business_day` |
| Push one local sale | POST | `/rest/v1/rpc/push_pos_transaction` |
| Pull cost-free POS product changes | POST | `/rest/v1/rpc/get_pos_products` |
| Pull inventory ledger changes | GET | `/rest/v1/inventory_ledger?updated_at=gt.<cursor>&select=...` |

Product and inventory pulls use separate successfully persisted cursors (`catalog-products` and `catalog-ledger`). Existing installations initially fall back to the legacy `catalog` cursor. Soft-deleted rows remain eligible for pulls so Room can hide them locally. The POS product RPC excludes `cost_price`.

## Device activation

The stall code, such as `MAIN-001`, identifies the stall during Cashier sign-in; it is not a device activation code. The System Administrator creates a separate one-time activation code in **Users & access**. Android redeems it with its hardware identifier and stores the returned cloud `device_id` in Room. Later sign-ins on that same active phone restore the device binding automatically. Successful redemption of a replacement code deactivates the previous POS for that stall, so an interrupted replacement does not lock out the working device. A used, foreign-stall, or replaced code is rejected.

## Operating days

Opening and closing use the same local record and idempotency key:

```json
{
  "p_day": {
    "id": "business-day-uuid",
    "stall_id": "stall-uuid",
    "device_id": "device-uuid",
    "business_date": "2026-09-08",
    "opened_at": "2026-09-08T01:00:00Z",
    "opening_notes": null,
    "closed_at": "2026-09-08T10:00:00Z",
    "closing_cash_total": 1250.00,
    "closing_notes": "Counted"
  }
}
```

Opening is required before checkout. Room permits only one record per stall and Manila business date. Closing records the completed local sales total between the opening and closing timestamps. Android pushes unsynced operating-day records before sales. The cloud sale RPC accepts a sale only when its timestamp falls inside the synced day window for the active device.

## Sale payload

```json
{
  "p_transaction": {
    "id": "local-transaction-uuid",
    "stall_id": "stall-uuid",
    "device_id": "device-uuid",
    "receipt_number": "LOCAL-202609080001",
    "status": "completed",
    "subtotal": 100.00,
    "total_amount": 100.00,
    "cash_received": 200.00,
    "change_amount": 100.00,
    "occurred_at": "2026-09-08T02:00:00Z",
    "items": [
      {
        "id": "local-item-uuid",
        "product_id": "product-uuid",
        "product_name": "Vanilla",
        "quantity": 2,
        "unit_price": 50.00,
        "line_total": 100.00
      }
    ]
  }
}
```

The server validates the Cashier role, stall, activated device, operating day, item values, products, and available stock. It groups repeated products, locks their rows to serialize competing sales, then inserts the transaction, items, and inventory ledger in one database transaction.

## Idempotency

The local transaction UUID is the sale idempotency key. Retrying the same payload is safe:

- `accepted`: the server inserted the record.
- `duplicate`: the server already has the same identity and total; Android marks the local row synced.
- `IDEMPOTENCY_CONFLICT`: the UUID or receipt number was reused with different data; Android keeps the row failed for review.

Operating-day pushes similarly accept the first open, merge the first close into that record, and safely acknowledge a retry.

## Response and retry rules

| Response or error | Android action |
|---|---|
| 200 | Commit the local acknowledgement or pulled changes and cursor |
| 400 / validation error | Retain the row with a permanent sync error |
| 401 / 403 | Surface the expired session or revoked-device error and require sign-in or activation |
| 409 / idempotency conflict | Retain the row for operator review |
| 408 / 425 / 429, 5xx, or network failure | Retry with WorkManager backoff |

Operating-day push runs before sale push, followed by product and inventory pull. A permanent failure stays in Room with its `syncError`; a failed pull does not advance its cursor.

## Recovery properties

- Checkout writes the transaction, items, sale ledger, and local stock inside one Room transaction.
- Network loss leaves local rows unsynced, and server-side UUID idempotency makes a retry safe after an uncertain response.
- Remote inventory deltas are applied through ledger entries instead of overwriting pending local sales.
- App upgrades use explicit Room migrations, including the device assignment and operating-day table added in database version 3.

These properties still require real-device and development-backend acceptance testing before production. See [PROJECT_REVIEW.md](PROJECT_REVIEW.md) for the remaining release work.
