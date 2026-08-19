import { createSupabaseClient, getSupabaseUrl } from './supabase'
import type { AppRole } from './types'

const storageKey = 'ice-cream-ims-session-token'

export type ManagerSession = {
  token: string
  userId: string
  stallId: string
  displayName: string
  role: AppRole
  expiresAt: string
}

type LoginRow = {
  session_token: string
  user_id: string
  stall_id: string
  display_name: string
  role: AppRole
  expires_at: string
}

export async function signIn(email: string, password: string): Promise<ManagerSession> {
  let data
  let error

  try {
    const response = await createSupabaseClient().rpc('login_with_password', {
      p_email: email,
      p_password: password,
    })
    data = response.data
    error = response.error
  } catch (caughtError) {
    const rawMessage = caughtError instanceof Error ? caughtError.message : String(caughtError)
    const supabaseUrl = getSupabaseUrl() ?? 'your Supabase project URL'
    throw new Error(
      rawMessage.toLowerCase().includes('fetch')
        ? `Cannot reach Supabase at ${supabaseUrl}. Check apps/web-ims/.env.local and confirm the project URL is correct.`
        : rawMessage,
    )
  }

  if (error || !data?.[0]) {
    const message = error?.message ?? 'Unable to sign in.'
    throw new Error(
      message.toLowerCase().includes('fetch')
        ? `Cannot reach Supabase at ${getSupabaseUrl() ?? 'your Supabase project URL'}. Check apps/web-ims/.env.local and confirm the project URL is correct.`
        : message,
    )
  }

  const row = data[0] as LoginRow
  const session = {
    token: row.session_token,
    userId: row.user_id,
    stallId: row.stall_id,
    displayName: row.display_name,
    role: row.role,
    expiresAt: row.expires_at,
  }
  sessionStorage.setItem(storageKey, JSON.stringify(session))
  return session
}

export function getStoredSession(): ManagerSession | null {
  const stored = sessionStorage.getItem(storageKey)
  if (!stored) return null

  try {
    const session = JSON.parse(stored) as Partial<ManagerSession>
    if (!session.token || !session.expiresAt || !session.displayName) {
      sessionStorage.removeItem(storageKey)
      return null
    }
    if (new Date(session.expiresAt) <= new Date()) {
      sessionStorage.removeItem(storageKey)
      return null
    }
    return {
      token: session.token,
      userId: session.userId ?? '',
      stallId: session.stallId ?? '',
      displayName: session.displayName,
      role: session.role ?? 'manager',
      expiresAt: session.expiresAt,
    }
  } catch {
    sessionStorage.removeItem(storageKey)
    return null
  }
}

export function signOut() {
  sessionStorage.removeItem(storageKey)
}
