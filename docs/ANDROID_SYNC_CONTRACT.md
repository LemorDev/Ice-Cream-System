# Coolerz Android POS API and Sync Contract

This document defines the contract used by the offline Android POS. Supabase PostgREST is the API gateway; the Android app uses Retrofit and the Supabase anon key.

## Base URL and headers

The base URL is the Supabase project URL with a trailing slash. Every request sends:

```http
apikey: <SUPABASE_ANON_KEY>
Authorization: Bearer <SUPABASE_ANON_KEY>
Accept-Profile: public
X-Session-Token: <custom session token>
X-Device-Id: <stable Android device identifier>
```

The service-role key is never shipped to Android. The custom `X-Session-Token` is returned by `login_with_password` and is evaluated by the database RLS functions.

## Endpoints

| Purpose | Method | Path |
|---|---:|---|
| Login | POST | `/rest/v1/rpc/login_with_password` |
| Push one local sale | POST | `/rest/v1/rpc/push_pos_transaction` |
| Pull product changes | GET | `/rest/v1/products?updated_at=gt.<cursor>&select=...` |
| Pull inventory changes | GET | `/rest/v1/inventory_ledger?updated_at=gt.<cursor>&select=...` |

The first pull omits the `updated_at` filter. Later pulls use the last successfully persisted cursor. Soft-deleted rows remain eligible for pulls so Room can hide them locally.

## Push payload

```json
{
  "id": "local-transaction-uuid",
  "stall_id": "stall-uuid",
  "device_id": null,
  "receipt_number": "LOCAL-202608140001",
  "status": "completed",
  "subtotal": 100.00,
  "total_amount": 100.00,
  "cash_received": 200.00,
  "change_amount": 100.00,
  "occurred_at": "2026-08-14T10:00:00Z",
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
```

The server validates the stall session, item quantities, and available stock. It inserts the transaction, transaction items, and sale inventory ledger in one database transaction.

## Idempotency

The local transaction UUID (`id`) is the idempotency key. The Android app generates it before writing the local checkout. Retrying the same payload is safe:

- `accepted`: the server inserted the sale.
- `duplicate`: the server already has the same transaction and total; Android marks the local row synced.
- `IDEMPOTENCY_CONFLICT`: the same UUID or receipt number was reused with a different total; Android keeps the row failed for operator review.

## Pull response

Product rows use the cloud schema fields `sale_price`, `cost_price`, `low_stock_threshold`, `pack_size`, and `conversion_rate`. Inventory rows use `quantity_delta`. Android calculates local stock as the ledger sum and stores the latest `updated_at` value as the catalog cursor.

## Response and retry rules

| Response or error | Meaning | Android action |
|---|---|---|
| 200 | Pull or RPC succeeded | Commit local changes/cursor |
| 400 / validation error | Invalid payload, insufficient stock, malformed UUID | Mark transaction permanent failure; do not retry automatically |
| 401 / 403 | Expired session, missing session, or revoked device | Stop sync and require sign-in/activation |
| 409 / idempotency conflict | Same key has different data | Mark permanent failure and surface it |
| 408 / 425 / 429 | Timeout, temporary availability, or rate limit | Retry with WorkManager backoff |
| 5xx | Server failure | Retry with WorkManager backoff |
| Network/DNS/SSL exception | Device cannot reach Supabase | Retry when connected |

Push always runs before pull. A permanent push failure for one transaction does not discard other queued transactions; each row keeps its own `syncError`. A failed pull does not advance the cursor.

## Recovery guarantees

- Power loss during checkout is safe because the transaction header, items, ledger entry, and local stock update use one Room transaction.
- Network loss before push leaves `isSynced = false`.
- Network loss after a successful server write is safe because the transaction UUID makes the retry a duplicate.
- Remote price changes affect new local carts after the next successful pull; an already-created local transaction retains its original price.
- Remote inventory changes are applied through ledger deltas and never overwrite a pending local sale.
- App upgrades use Room migrations; failed sync rows remain in the local database for recovery.
