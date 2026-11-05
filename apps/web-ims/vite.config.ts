import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

function projectIdentity(value: string | undefined) {
  if (!value) return ''
  try { return new URL(value).host.toLowerCase() } catch { return value.trim().replace(/\/$/, '').toLowerCase() }
}

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), '')
  const appEnvironment = environment.VITE_APP_ENV
  const supabaseUrl = environment.VITE_SUPABASE_URL
  const supabaseAnonKey = environment.VITE_SUPABASE_ANON_KEY

  if (!supabaseUrl || !supabaseAnonKey) {
    throw new Error(`Supabase is not configured for ${mode}. Add the URL and anon key to the matching .env.${mode}.local file.`)
  }
  if (!appEnvironment) {
    throw new Error(`Set VITE_APP_ENV explicitly in the matching .env.${mode}.local file or deployment environment.`)
  }
  if (!['development', 'production'].includes(appEnvironment)) {
    throw new Error('VITE_APP_ENV must be development or production.')
  }
  if (mode === 'development' && appEnvironment !== 'development') {
    throw new Error('The Vite development server must use VITE_APP_ENV=development.')
  }
  if (mode === 'production' && appEnvironment !== 'production') {
    throw new Error('Production builds must use VITE_APP_ENV=production. Use the development build command for test environments.')
  }
  if (appEnvironment === 'development') {
    const productionUrl = environment.VITE_SUPABASE_PRODUCTION_URL
    if (!productionUrl) throw new Error('Set VITE_SUPABASE_PRODUCTION_URL so the development build can verify it is isolated.')
    if (projectIdentity(supabaseUrl) === projectIdentity(productionUrl)) {
      throw new Error('The development IMS points at the production Supabase project. Configure a separate development project before launching IMS.')
    }
  }

  return {
    plugins: [react(), tailwindcss()],
    define: { __COOLERZ_ENV__: JSON.stringify(appEnvironment) },
  }
})
