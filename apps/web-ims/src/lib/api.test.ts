import assert from 'node:assert/strict'
import test from 'node:test'
import { createClient } from '@supabase/supabase-js'
import { archiveCategory, archiveProduct, getOverheadForStall, updateStall } from './api.ts'

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
