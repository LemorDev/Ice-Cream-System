import { createSupabaseClient, getSupabaseUrl } from './supabase'
import type { AppRole } from './types'

const storageKey = 'ice-cream-ims-session-token'

export type AppSession = {
  token: string
  userId: string
  stallId: string
  displayName: string
  role: AppRole
  expiresAt: string
  adminVerifiedAt?: string
}

type LoginRow = {
  session_token: string
  user_id: string
  stall_id: string
  display_name: string
  role: AppRole
  expires_at: string
}

export async function signIn(identifier: string, password: string): Promise<AppSession> {
  let data
  let error
  let status

  try {
    const response = await createSupabaseClient().rpc('login_with_password', {
      p_identifier: identifier,
      p_password: password,
    })
    data = response.data
    error = response.error
    status = response.status
  } catch (caughtError) {
    const rawMessage = caughtError instanceof Error ? caughtError.message : String(caughtError)
    const supabaseUrl = getSupabaseUrl() ?? 'your Supabase project URL'
    throw new Error(
      rawMessage.toLowerCase().includes('fetch')
        ? `Cannot reach Supabase at ${supabaseUrl}. Check apps/web-ims/.env.local and confirm the project URL is correct.`
        : rawMessage,
    )
  }

  if (status === 429) {
    throw new Error('Too many sign-in attempts from this network. Wait a minute and try again.')
  }
  if (error && error.code !== '28000') {
    throw new Error(
      error.message?.toLowerCase().includes('fetch')
        ? `Cannot reach Supabase at ${getSupabaseUrl() ?? 'your Supabase project URL'}. Check apps/web-ims/.env.local and confirm the project URL is correct.`
        : 'Unable to sign in right now. Try again shortly.',
    )
  }

  const row = data?.[0] as LoginRow | undefined
  if (!row) {
    throw new Error('Invalid Credentials Please Try Again. If you’ve tried several times, wait 15 minutes before trying again.')
  }
  const session = {
    token: row.session_token,
    userId: row.user_id,
    stallId: row.stall_id,
    displayName: row.display_name,
    role: row.role,
    expiresAt: row.expires_at,
  }
  storeSession(session)
  return session
}

export function getStoredSession(): AppSession | null {
  const stored = sessionStorage.getItem(storageKey)
  if (!stored) return null

  try {
    const session = JSON.parse(stored) as Partial<AppSession>
    if (!session.token || !session.expiresAt || !session.displayName || !['system_admin', 'owner', 'cashier'].includes(session.role ?? '')) {
      sessionStorage.removeItem(storageKey)
      return null
    }
    const expiresAt = Date.parse(session.expiresAt)
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      sessionStorage.removeItem(storageKey)
      return null
    }
    return {
      token: session.token,
      userId: session.userId ?? '',
      stallId: session.stallId ?? '',
      displayName: session.displayName,
      role: session.role as AppRole,
      expiresAt: session.expiresAt,
      adminVerifiedAt: session.adminVerifiedAt,
    }
  } catch {
    sessionStorage.removeItem(storageKey)
    return null
  }
}

export function storeSession(session: AppSession) {
  sessionStorage.setItem(storageKey, JSON.stringify(session))
}

export async function signOut(token?: string): Promise<boolean> {
  sessionStorage.removeItem(storageKey)
  if (!token) return true

  for (let attempt = 0; attempt < 2; attempt++) {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 3_000)
    try {
      const { error } = await createSupabaseClient(token).rpc('revoke_app_session').abortSignal(controller.signal)
      if (!error) return true
    } catch {
      // Retry once with the captured bearer held only in this function.
    } finally {
      window.clearTimeout(timeout)
    }
  }
  return false
}
