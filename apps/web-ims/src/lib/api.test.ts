import assert from 'node:assert/strict'
import test from 'node:test'
import { createClient } from '@supabase/supabase-js'
import { archiveProduct, getOverheadForStall, updateStall, uploadProductImage, validateProductImage } from './api.ts'

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

test('product images accept supported formats and reject empty, oversized and unsupported files', () => {
  for (const type of ['image/jpeg', 'image/png', 'image/webp']) {
    assert.doesNotThrow(() => validateProductImage({ type, size: 5 * 1024 * 1024 }))
  }
  assert.throws(() => validateProductImage({ type: 'image/svg+xml', size: 100 }), /JPG, PNG or WebP/)
  assert.throws(() => validateProductImage({ type: 'image/png', size: 0 }), /5 MB/)
  assert.throws(() => validateProductImage({ type: 'image/png', size: 5 * 1024 * 1024 + 1 }), /5 MB/)
})

test('upload stores a unique image under its stall and returns the public POS URL', async () => {
  let requestUrl = ''
  let requestHeaders = new Headers()
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: {
      headers: { 'X-Session-Token': 'admin-session' },
      fetch: async (input, init) => {
        requestUrl = String(input)
        requestHeaders = new Headers(init?.headers)
        return new Response(JSON.stringify({ Key: 'product-images/stall-1/photo.png' }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        })
      },
    },
  })
  const url = await uploadProductImage(client, 'stall-1', new File(['image'], 'photo.png', { type: 'image/png' }))
  assert.match(requestUrl, /\/storage\/v1\/object\/product-images\/stall-1\/[\da-f-]+\.png$/)
  assert.match(url, /\/storage\/v1\/object\/public\/product-images\/stall-1\/[\da-f-]+\.png$/)
  assert.equal(requestHeaders.get('X-Session-Token'), 'admin-session')
  assert.equal(requestHeaders.get('x-upsert'), 'false')
})

test('failed image uploads report the error instead of returning a broken image URL', async () => {
  const client = createClient('https://example.invalid', 'test-key', {
    auth: { persistSession: false },
    global: {
      fetch: async () => new Response(JSON.stringify({ message: 'Permission denied' }), {
        status: 403, headers: { 'Content-Type': 'application/json' },
      }),
    },
  })
  await assert.rejects(uploadProductImage(client, 'stall-1', new File(['image'], 'photo.png', { type: 'image/png' })), /Product picture upload failed: Permission denied/)
})
