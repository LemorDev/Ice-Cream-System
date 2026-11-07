// Real P-256 signatures and a disposable in-memory HTTP stub. No live requests.
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const base = 'https://database.invalid';
const bearer = 'a'.repeat(64);
const device = '1234567890abcdef';
const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const encodedKey = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const hash = (data) => createHash('sha256').update(data).digest('hex');
const permits = new Set();
const relayed = [];
let handler;
globalThis.Deno = {
  env: { get: (key) => ({
    SUPABASE_URL: base, SUPABASE_ANON_KEY: 'public-test-key', SUPABASE_SERVICE_ROLE_KEY: 'private-test-key',
  })[key] },
  serve: (callback) => { handler = callback; },
};
globalThis.fetch = async (url, options) => {
  assert.ok(url.startsWith(`${base}/rest/v1/`));
  if (url.endsWith('/issue_pos_request_permit')) {
    assert.equal(options.headers.Authorization, 'Bearer private-test-key');
    const input = JSON.parse(options.body);
    assert.equal(input.p_public_key, encodedKey);
    assert.equal(input.p_hardware_id, device);
    if (permits.has(input.p_nonce)) return Response.json(null);
    permits.add(input.p_nonce);
    return Response.json('b'.repeat(64));
  }
  assert.equal(options.headers.get('Authorization'), 'Bearer public-test-key');
  assert.equal(options.headers.get('X-Pos-Permit'), 'b'.repeat(64));
  assert.equal(options.headers.get('X-Client-Injected'), null);
  relayed.push({ url, body: new TextDecoder().decode(options.body) });
  return Response.json({ status: 'accepted' });
};

await import('../functions/pos-gateway/index.ts');

function signedRequest(path, body, { timestamp = Math.floor(Date.now() / 1000).toString(),
  nonce = randomUUID(), token = bearer, method = 'POST' } = {}) {
  const message = ['coolerz-pos-proof-v1', device, method, path, timestamp, nonce,
    token ? hash(token) : '', hash(body)].join('\n');
  const signature = sign('sha256', Buffer.from(message), privateKey).toString('base64');
  return { 'X-Device-Id': device, 'X-Session-Token': token, 'X-Pos-Public-Key': encodedKey,
    'X-Pos-Signature': signature, 'X-Pos-Timestamp': timestamp, 'X-Pos-Nonce': nonce };
}

const request = (path, body, headers, method = 'POST') => new Request(`https://relay.invalid/functions/v1/pos-gateway${path}`, {
  method, headers, body: method === 'POST' ? body : undefined,
});
const sale = '/rpc/push_pos_transaction_v2';
const payload = '{"p_transaction":{"total_amount":50}}';
const proof = signedRequest(sale, payload);
assert.equal((await handler(request(sale, payload, { 'X-Device-Id': device, 'X-Session-Token': bearer }))).status, 403);
assert.equal((await handler(request(sale, payload.replace('50', '1'), proof))).status, 403);
assert.equal((await handler(request(sale, payload, { ...proof, 'X-Session-Token': 'c'.repeat(64) }))).status, 403);
assert.equal((await handler(request('/rpc/push_business_day', payload, proof))).status, 403);
assert.equal((await handler(request(sale, payload, signedRequest(sale, payload, {
  timestamp: (Math.floor(Date.now() / 1000) - 91).toString(),
})))).status, 403);
assert.equal(permits.size, 0, 'A denied signature reached the permit issuer');
assert.equal((await handler(request(sale, payload, { ...proof, 'X-Client-Injected': 'ignore' }))).status, 200);
assert.equal((await handler(request(sale, payload, proof))).status, 403);
assert.deepEqual(relayed, [{ url: `${base}/rest/v1${sale}`, body: payload }]);

const query = '/inventory_ledger?select=id&updated_at=gt.2026-11-01';
const queryProof = signedRequest(query, '', { method: 'GET' });
assert.equal((await handler(request(query.replace('11-01', '10-01'), '', queryProof, 'GET'))).status, 403);
assert.equal((await handler(request(query, '', queryProof, 'GET'))).status, 200);
assert.equal((await handler(request('/rpc/issue_pos_request_permit', '{}', signedRequest('/rpc/issue_pos_request_permit', '{}')))).status, 404);

const apiSource = await readFile('apps/android-pos/app/src/main/java/com/icecreampost/pos/data/remote/SupabaseApi.kt', 'utf8');
for (const [, name] of apiSource.matchAll(/@POST\("rest\/v1\/rpc\/([^"]+)"\)/g)) {
  if (name === 'revoke_app_session') continue;
  const path = `/rpc/${name}`;
  const headers = signedRequest(path, '{}', { token: name === 'login_pos_with_password' ? '' : bearer });
  assert.equal((await handler(request(path, '{}', headers))).status, 200, `Android RPC is missing from the relay allowlist: ${name}`);
}
console.log('PASS: Real P-256 request signatures, replay rejection, signed payload and query, restricted relay routes');
