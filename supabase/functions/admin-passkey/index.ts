import {
  generateAuthenticationOptions, generateRegistrationOptions,
  verifyAuthenticationResponse, verifyRegistrationResponse,
  type AuthenticationResponseJSON, type RegistrationResponseJSON,
} from 'npm:@simplewebauthn/server@14.0.3'

type Credential = { id: string; public_key: string; counter: number }
type Context = { user_id: string; keys: Credential[] }
type Challenge = { challenge: string; enrollment_hash: string | null }

const expectedOrigin = new URL(Deno.env.get('ADMIN_WEBAUTHN_ORIGIN') ?? '')
if (expectedOrigin.protocol !== 'https:' && expectedOrigin.origin !== 'http://localhost:5173') {
  throw new Error('Set ADMIN_WEBAUTHN_ORIGIN to the exact HTTPS web origin.')
}
const rpID = expectedOrigin.hostname
const supabaseUrl = Deno.env.get('SUPABASE_URL')
const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
if (!supabaseUrl || !serviceKey) throw new Error('Supabase verifier configuration is missing.')

async function rpc<T>(name: string, args: Record<string, unknown>): Promise<T> {
  const response = await fetch(`${supabaseUrl}/rest/v1/rpc/${name}`, {
    method: 'POST', redirect: 'error',
    headers: { apikey: serviceKey!, Authorization: `Bearer ${serviceKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(args),
  })
  if (!response.ok) {
    const result = await response.json().catch(() => null)
    if (result?.message === 'ENROLLMENT_CODE_INVALID') throw new Error('The enrollment code is invalid or expired.')
    throw new Error('Passkey verification failed. Try again.')
  }
  return await response.json() as T
}

function encodeKey(value: Uint8Array) {
  return btoa(String.fromCharCode(...value)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
}

function decodeKey(value: string) {
  return Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), (char) => char.charCodeAt(0))
}

Deno.serve(async (request) => {
  if (request.headers.get('Origin') !== expectedOrigin.origin) return new Response(null, { status: 403 })
  const headers = {
    'Access-Control-Allow-Origin': expectedOrigin.origin,
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'apikey, authorization, content-type, x-client-info, x-session-token',
    'Cache-Control': 'no-store',
    'Vary': 'Origin',
  }
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers })
  const respond = (data: unknown, status = 200) => Response.json(data, { status, headers })
  if (request.method !== 'POST') return respond({ error: 'Method not allowed.' }, 405)
  const token = request.headers.get('X-Session-Token')
  if (!token || !/^[0-9a-f]{64}$/.test(token)) return respond({ error: 'Sign in again.' }, 401)

  try {
    const raw = await request.text()
    if (raw.length > 32_768) return respond({ error: 'Passkey request is too large.' }, 413)
    const input = JSON.parse(raw)
    const context = await rpc<Context | null>('admin_passkey_context', { p_token: token })
    if (!context) return respond({ error: 'Sign in again as a System Administrator.' }, 401)

    if (input.action === 'begin') {
      const code = input.code ?? null
      if (code !== null && (typeof code !== 'string' || !/^[0-9a-f]{48}$/.test(code))) {
        return respond({ error: 'Enter a valid enrollment code.' }, 400)
      }
      if (code === null && !context.keys.length) return respond({ error: 'Enroll a passkey with a code from your database administrator.' }, 409)
      const options = code !== null ? await generateRegistrationOptions({
        rpName: 'Coolerz IMS', rpID, userName: context.user_id,
        userID: new TextEncoder().encode(context.user_id),
        userDisplayName: 'Coolerz System Administrator',
        authenticatorSelection: { residentKey: 'preferred', userVerification: 'required' },
        attestationType: 'none', excludeCredentials: context.keys.map(({ id }) => ({ id })),
      }) : await generateAuthenticationOptions({
        rpID, userVerification: 'required', allowCredentials: context.keys.map(({ id }) => ({ id })),
      })
      const challengeId = await rpc<string>('begin_admin_passkey', {
        p_token: token, p_challenge: options.challenge, p_enrollment_code: code,
      })
      return respond({ challengeId, options })
    }

    if (input.action !== 'enroll' && input.action !== 'verify') return respond({ error: 'Invalid passkey request.' }, 400)
    const challenge = await rpc<Challenge | null>('consume_admin_passkey_challenge', {
      p_token: token, p_challenge_id: input.challengeId, p_enrolling: input.action === 'enroll',
    })
    if (!challenge) return respond({ error: 'The passkey request expired. Try again.' }, 403)

    const expectations = { expectedOrigin: expectedOrigin.origin, expectedRPID: rpID,
      expectedChallenge: challenge.challenge, requireUserVerification: true }
    const finish = async (credentialId: string, key: string | null, oldCounter: number | null, newCounter: number) => {
      const verifiedAt = await rpc<string>('finish_admin_passkey', {
        p_token: token, p_credential_id: credentialId, p_public_key: key,
        p_old_counter: oldCounter, p_new_counter: newCounter, p_enrollment_hash: challenge.enrollment_hash,
      })
      return respond({ verifiedAt })
    }
    if (input.action === 'enroll') {
      const result = await verifyRegistrationResponse({ ...expectations, response: input.response as RegistrationResponseJSON })
      if (!result.verified || !result.registrationInfo) return respond({ error: 'Passkey enrollment failed.' }, 403)
      const { id, publicKey, counter } = result.registrationInfo.credential
      return await finish(id, encodeKey(publicKey), null, counter)
    }
    const response = input.response as AuthenticationResponseJSON
    const key = context.keys.find(({ id }) => id === response.id)
    if (!key) return respond({ error: 'Use a passkey enrolled for this account.' }, 403)
    const result = await verifyAuthenticationResponse({ ...expectations, response, credential: {
      id: key.id, publicKey: decodeKey(key.public_key), counter: key.counter,
    } })
    if (!result.verified) return respond({ error: 'Passkey verification failed.' }, 403)
    return await finish(key.id, null, key.counter, result.authenticationInfo.newCounter)
  } catch (error) {
    const enrollmentError = error instanceof Error && error.message === 'The enrollment code is invalid or expired.'
    return respond({ error: enrollmentError ? error.message : 'Passkey verification could not finish. Try again.' }, 403)
  }
})
