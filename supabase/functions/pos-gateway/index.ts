const supabaseUrl = Deno.env.get('SUPABASE_URL')
const anonKey = Deno.env.get('SUPABASE_ANON_KEY')
const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
if (!supabaseUrl || !anonKey || !serviceKey) throw new Error('POS verifier configuration is missing.')

const rpcNames = new Set([
  'activate_pos_device', 'get_pos_catalog_revision', 'get_pos_day_closing_counts',
  'get_pos_inventory_ledger_page_v2', 'get_pos_products', 'get_pos_products_page',
  'get_pos_recipes', 'get_pos_recipes_page', 'get_resumable_pos_day',
  'login_pos_with_password', 'prepare_pos_replacement', 'push_business_day',
  'push_daily_store_closing_v2', 'push_pos_inventory_entry_v2', 'push_pos_pack_unseal',
  'push_pos_powder_opening', 'push_pos_sale_reversal', 'push_pos_transaction_v2',
  'push_revenue_deduction_v2',
])
const encoder = new TextEncoder()
const maxBodyBytes = 1024 * 1024

const decode = (value: string) => Uint8Array.from(atob(value), (char) => char.charCodeAt(0))
const hex = (value: ArrayBuffer) => Array.from(new Uint8Array(value), (byte) => byte.toString(16).padStart(2, '0')).join('')

// Android's SHA256withECDSA emits DER. Web Crypto verifies fixed-width r || s.
function rawSignature(der: Uint8Array) {
  if (der[0] !== 0x30 || der[1] !== der.length - 2 || der.length > 72) throw new Error('Invalid signature')
  const raw = new Uint8Array(64)
  let cursor = 2
  for (const offset of [0, 32]) {
    if (der[cursor++] !== 2) throw new Error('Invalid signature')
    const length = der[cursor++]
    const integer = der.slice(cursor, cursor + length)
    if (length < 1 || length > 33 || integer.length !== length || integer[0] >= 128 ||
      (integer.length > 1 && integer[0] === 0 && integer[1] < 128)) throw new Error('Invalid signature')
    const magnitude = integer[0] === 0 ? integer.subarray(1) : integer
    if (magnitude.length > 32) throw new Error('Invalid signature')
    raw.set(magnitude, offset + 32 - magnitude.length)
    cursor += length
  }
  if (cursor !== der.length) throw new Error('Invalid signature')
  return raw
}

async function readBody(request: Request) {
  if (!request.body) return new Uint8Array(0)
  const reader = request.body.getReader()
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    while (true) {
      const chunk = await reader.read()
      if (chunk.done) break
      size += chunk.value.byteLength
      if (size > maxBodyBytes) {
        await reader.cancel()
        return null
      }
      chunks.push(chunk.value)
    }
  } finally {
    reader.releaseLock()
  }
  const body = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) {
    body.set(chunk, offset)
    offset += chunk.byteLength
  }
  return body
}

const reply = (status: number, message: string) =>
  Response.json({ message }, { status, headers: { 'Cache-Control': 'no-store' } })

Deno.serve(async (request) => {
  const url = new URL(request.url)
  const path = url.pathname.replace(/^(?:\/functions\/v1)?\/pos-gateway(?=\/)/, '')
  if (request.method === 'GET' ? path !== '/inventory_ledger' :
    request.method !== 'POST' || !path.startsWith('/rpc/') || !rpcNames.has(path.slice(5)) || url.search) {
    return reply(404, 'Unknown POS operation.')
  }
  const headers = request.headers
  const hardware = headers.get('x-device-id') ?? ''
  const token = headers.get('x-session-token') ?? ''
  const publicKey = headers.get('x-pos-public-key') ?? ''
  const signature = headers.get('x-pos-signature') ?? ''
  const timestamp = headers.get('x-pos-timestamp') ?? ''
  const nonce = headers.get('x-pos-nonce') ?? ''
  const login = path === '/rpc/login_pos_with_password'
  if (!/^[0-9a-f]{1,16}$/.test(hardware) || (login ? token !== '' : !/^[0-9a-f]{64}$/.test(token)) ||
    !/^[A-Za-z0-9+/]{100,148}={0,2}$/.test(publicKey) || !/^[A-Za-z0-9+/]{80,100}={0,2}$/.test(signature) ||
    !/^\d{10}$/.test(timestamp) || Math.abs(Date.now() / 1000 - Number(timestamp)) > 90 ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(nonce)) {
    return reply(403, 'POS_DEVICE_PROOF_REQUIRED')
  }
  try {
    const body = await readBody(request)
    if (!body) return reply(413, 'POS request is too large.')
    const sessionHash = token ? hex(await crypto.subtle.digest('SHA-256', encoder.encode(token))) : ''
    const bodyHash = hex(await crypto.subtle.digest('SHA-256', body))
    const message = ['coolerz-pos-proof-v1', hardware, request.method, path + url.search, timestamp,
      nonce, sessionHash, bodyHash].join('\n')
    let verified = false
    try {
      const key = await crypto.subtle.importKey('spki', decode(publicKey),
        { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify'])
      verified = await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key,
        rawSignature(decode(signature)), encoder.encode(message))
    } catch { /* Malformed key or signature. */ }
    if (!verified) return reply(403, 'POS_DEVICE_PROOF_INVALID')

    // Consume the nonce in a separate committed transaction. Read-only RPCs
    // can then validate the short-lived, server-only permit without writing.
    const address = headers.get('cf-connecting-ip')
    const authorization = await fetch(`${supabaseUrl}/rest/v1/rpc/issue_pos_request_permit`, {
      method: 'POST', redirect: 'error',
      headers: { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        p_hardware_id: hardware, p_nonce: nonce, p_public_key: publicKey, p_session_hash: sessionHash || null,
        p_method: request.method, p_path: path,
        p_client_address: address && /^[0-9a-f:.]{2,45}$/i.test(address) ? address : null,
      }),
    })
    if (authorization.status === 429) {
      return new Response(null, { status: 429, headers: {
        'Cache-Control': 'no-store', 'Retry-After': authorization.headers.get('Retry-After') ?? '60',
      } })
    }
    if (!authorization.ok) return reply(503, 'POS device verification is unavailable.')
    const permit = await authorization.json()
    if (typeof permit !== 'string' || !/^[0-9a-f]{64}$/.test(permit)) return reply(403, 'POS_DEVICE_PROOF_DENIED')

    const upstream = new Headers({ apikey: anonKey, 'Accept-Profile': 'public',
      'X-Device-Id': hardware, 'X-Pos-Permit': permit })
    if (!anonKey.startsWith('sb_publishable_')) upstream.set('Authorization', `Bearer ${anonKey}`)
    if (token) upstream.set('X-Session-Token', token)
    if (request.method === 'POST') upstream.set('Content-Type', 'application/json')
    const response = await fetch(`${supabaseUrl}/rest/v1${path}${url.search}`, {
      method: request.method, redirect: 'error', headers: upstream,
      body: request.method === 'POST' ? body : undefined,
    })
    const output = new Headers({ 'Cache-Control': 'no-store' })
    for (const name of ['Content-Type', 'Retry-After']) {
      const value = response.headers.get(name)
      if (value) output.set(name, value)
    }
    return new Response(response.body, { status: response.status, headers: output })
  } catch {
    return reply(503, 'POS device verification is unavailable.')
  }
})
