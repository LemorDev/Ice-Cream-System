import { createClient } from '@supabase/supabase-js'

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY

export function getSupabaseUrl() {
  return supabaseUrl
}

function getConfiguration() {
  if (!supabaseUrl || !supabaseAnonKey) {
    throw new Error('Supabase is not configured. Add VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY to .env.local.')
  }

  return { supabaseUrl, supabaseAnonKey }
}

export function createSupabaseClient(sessionToken?: string) {
  const configuration = getConfiguration()

  return createClient(configuration.supabaseUrl, configuration.supabaseAnonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: sessionToken ? { headers: { 'X-Session-Token': sessionToken } } : undefined,
  })
}
