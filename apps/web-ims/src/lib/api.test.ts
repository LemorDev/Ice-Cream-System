import assert from 'node:assert/strict'
import test from 'node:test'
import { createClient } from '@supabase/supabase-js'
import { archiveCategory, archiveProduct, getOverheadForStall, getTransactionReceiptItems, resetStockLevels, resetTransactionsAndRevenue, saveProductWithRecipe, setStockOnHand, updateStall } from './api.ts'

test('product save keeps POS category separate from flavor for catalog sync', async () => {
  let request: { url: string; body: { p_product: { category_id: string; sell_category: string } } } | undefined
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: { fetch: async (input, init) => {
      if (String(input).includes('/products?')) return new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })
      request = { url: String(input), body: JSON.parse(String(init?.body)) }
      return new Response(JSON.stringify({ id: 'menu-1', name: 'Vanilla Cup' }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    } },
  })
  await saveProductWithRecipe(client, {
    stall_id: 'stall-1', category_id: 'flavor-1', sell_category: 'Cup', name: 'Vanilla Cup',
    unit: 'piece', sale_price: 40, cost_price: 10, low_stock_threshold: 0,
    pack_size: 1, conversion_rate: 1, is_sellable: true, product_type: 'sellable', base_unit: 'piece',
  }, [], 'menu-1')
  assert.match(request?.url ?? '', /save_product_with_recipe/)
  assert.equal(request?.body.p_product.category_id, 'flavor-1')
  assert.equal(request?.body.p_product.sell_category, 'Cup')
})

test('product save stops before mutation when the POS category migration is missing', async () => {
  let requests = 0
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: { fetch: async () => {
      requests += 1
      return new Response(JSON.stringify({ message: 'column products.sell_category does not exist' }), {
        status: 400, headers: { 'Content-Type': 'application/json' },
      })
    } },
  })
  await assert.rejects(saveProductWithRecipe(client, {
    stall_id: 'stall-1', category_id: null, sell_category: 'Cone', name: 'Vanilla Cone',
    unit: 'piece', sale_price: 40, cost_price: 10, low_stock_threshold: 0,
    pack_size: 1, conversion_rate: 1, is_sellable: true, product_type: 'sellable', base_unit: 'piece',
  }, []), /POS categories are not ready/)
  assert.equal(requests, 1)
})

test('receipt items are fetched for the selected transaction with numeric amounts', async () => {
  let requestedUrl = ''
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: { fetch: async (input) => {
      requestedUrl = String(input)
      return new Response(JSON.stringify([{ id: 'item-1', transaction_id: 'sale-1', product_id: 'menu-1', product_name: 'Twirl', quantity: '2', unit_price: '25', line_total: '50' }]), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    } },
  })
  const items = await getTransactionReceiptItems(client, 'sale-1')
  assert.match(requestedUrl, /transaction_id=eq.sale-1/)
  assert.equal(items[0].quantity, 2)
  assert.equal(items[0].line_total, 50)
})

test('archiving succeeds when the database returns a successful empty response', async () => {
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: { fetch: async () => new Response(null, { status: 204 }) },
  })

  await assert.doesNotReject(archiveProduct(client, 'product-1'))
})

test('archiving still reports database errors', async () => {
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: {
      fetch: async () => new Response(JSON.stringify({ message: 'Permission denied', code: '42501' }), {
        status: 403,
        headers: { 'Content-Type': 'application/json' },
      }),
    },
  })

  await assert.rejects(archiveProduct(client, 'product-1'), /Permission denied/)
})

test('deleting a flavor soft-deletes its database category', async () => {
  let requestBody: Record<string, unknown> = {}
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: {
      fetch: async (_input, init) => {
        requestBody = JSON.parse(String(init?.body)) as Record<string, unknown>
        return new Response(null, { status: 204 })
      },
    },
  })

  await archiveCategory(client, 'flavor-1')

  assert.equal(typeof requestBody.deleted_at, 'string')
})

test('data reset actions call their protected stall RPCs', async () => {
  const requests: Array<{ url: string; body: Record<string, unknown> }> = []
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: {
      fetch: async (input, init) => {
        requests.push({ url: String(input), body: JSON.parse(String(init?.body)) as Record<string, unknown> })
        return new Response(JSON.stringify(requests.length === 1
          ? { scope: 'operating_history', affected_records: 3, affected_operating_days: 2 }
          : { scope: 'stock', affected_records: 3 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      },
    },
  })

  const historyResult = await resetTransactionsAndRevenue(client, 'stall-1')
  await resetStockLevels(client, 'stall-1')

  assert.match(requests[0].url, /reset_stall_transactions/)
  assert.match(requests[1].url, /reset_stall_stock/)
  assert.deepEqual(requests.map((request) => request.body), [{ p_stall_id: 'stall-1' }, { p_stall_id: 'stall-1' }])
  assert.deepEqual(historyResult, { scope: 'operating_history', affected_records: 3, affected_operating_days: 2 })
})

test('a failed overhead update is reported instead of falling back to a partial save', async () => {
  let requests = 0
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: {
      fetch: async () => {
        requests += 1
        return new Response(JSON.stringify({ message: 'Invalid overhead configuration', code: '23514' }), {
          status: 400,
          headers: { 'Content-Type': 'application/json' },
        })
      },
    },
  })

  await assert.rejects(updateStall(client, 'stall-1', {
    name: 'Coolerz', overhead_config: [{ key: 'rent', label: 'Rent', dailyRate: -1 }],
  }), /Invalid overhead configuration/)
  assert.equal(requests, 1)
})

test('saved overhead comes from the database response', async () => {
  let requestUrl = ''
  let requestBody: Record<string, unknown> = {}
  const saved = {
    id: 'stall-1', name: 'Coolerz', code: 'MAIN', updated_at: '2026-09-08T00:00:00Z',
    overhead_config: [{ key: 'rent', label: 'Rent', dailyRate: 200 }],
  }
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: {
      fetch: async (input, init) => {
        requestUrl = String(input)
        requestBody = JSON.parse(String(init?.body)) as Record<string, unknown>
        return new Response(JSON.stringify(saved), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        })
      },
    },
  })

  const result = await updateStall(client, saved.id, { overhead_config: saved.overhead_config })
  assert.deepEqual(getOverheadForStall(result), saved.overhead_config)
  assert.match(requestUrl, /\/rpc\/update_managed_stall$/)
  assert.deepEqual(requestBody, {
    p_stall_id: 'stall-1', p_name: null, p_code: null, p_overhead_config: saved.overhead_config,
  })
})

test('physical stock count sends the target base units to the atomic correction endpoint', async () => {
  let requestUrl = ''
  let requestBody: Record<string, unknown> = {}
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: { fetch: async (input, init) => {
      requestUrl = String(input)
      requestBody = JSON.parse(String(init?.body)) as Record<string, unknown>
      return new Response(JSON.stringify({ status: 'adjusted', previous_stock: 10, target_stock: 10_000, quantity_delta: 9_990 }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    } },
  })

  const result = await setStockOnHand(client, 'stall-1', 'powder-1', 10_000, 'Correct test receipt')

  assert.match(requestUrl, /\/rpc\/set_stock_on_hand$/)
  assert.deepEqual(requestBody, {
    p_stall_id: 'stall-1', p_product_id: 'powder-1', p_target_stock: 10_000,
    p_reason: 'Correct test receipt',
  })
  assert.equal(result.quantity_delta, 9_990)
})
