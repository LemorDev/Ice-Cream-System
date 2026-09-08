import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { listAccessibleStalls, loadWorkspace } from './lib/api'
import { startWorkspaceAutoRefresh } from './lib/live-sync'
import { getStoredSession, signIn, signOut, type AppSession } from './lib/session'
import { createSupabaseClient } from './lib/supabase'
import type { Stall, WorkspaceData } from './lib/types'
import { Button, LoadingState, Notice, Select } from './components/ui'
import {
  AdjustmentsScreen,
  OperatingDaysScreen,
  OverviewScreen,
  ProductsScreen,
  PricingScreen,
  ReceivingScreen,
  ReportsScreen,
  StallScreen,
  StaffScreen,
  TransactionsScreen,
} from './screens'
import './styles.css'

type View = 'overview' | 'stall' | 'staff' | 'products' | 'receiving' | 'adjustments' | 'pricing' | 'transactions' | 'reports' | 'days'

const brandLogo = '/branding/coolerz-icecream-logo.png'

const viewLabels: Record<View, string> = {
  overview: 'Overview',
  stall: 'Stall settings',
  staff: 'Staff & devices',
  products: 'Products',
  receiving: 'Receive stock',
  adjustments: 'Adjust inventory',
  pricing: 'Prices & conversions',
  transactions: 'Transactions',
  reports: 'Sales reports',
  days: 'Operating days',
}

const navGroups: Array<{ label: string; items: View[] }> = [
  { label: 'Workspace', items: ['overview', 'stall', 'staff'] },
  { label: 'Inventory', items: ['products', 'receiving', 'adjustments', 'pricing'] },
  { label: 'Sales', items: ['transactions', 'reports', 'days'] },
]

function BrandMark({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`flex items-center gap-3 ${compact ? '' : 'rounded-2xl border border-white/15 bg-white/8 px-4 py-3 shadow-lg shadow-black/15 backdrop-blur'}`}>
      <img
        alt="Coolerz Ice Cream logo"
        className={`${compact ? 'h-12 w-12 rounded-2xl border border-[#eadcff] bg-white object-cover shadow-md' : 'h-16 w-16 rounded-2xl border border-white/20 bg-white object-cover shadow-md'}`}
        src={brandLogo}
      />
      <div>
        <p className={`text-xs font-semibold uppercase tracking-[0.28em] ${compact ? 'text-[#efe1ff]' : 'text-[#f4dcff]'}`}>Coolerz IMS</p>
        <p className={`mt-1 ${compact ? 'text-sm text-[#f5edf8]' : 'text-sm text-[#f8eefe]'}`}>Shop management console</p>
      </div>
    </div>
  )
}

function LoginScreen({ onSignedIn }: { onSignedIn: (session: AppSession) => void }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [isLoading, setIsLoading] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')

    if (!email.trim() || password.length < 1) {
      setError('Enter your email and password.')
      return
    }

    setIsLoading(true)
    try {
      onSignedIn(await signIn(email, password))
      setPassword('')
    } catch (caughtError) {
      setError(caughtError instanceof Error ? caughtError.message : 'Unable to sign in.')
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-[radial-gradient(circle_at_top,_#fff8ea_0%,_#efe1ff_38%,_#220046_100%)] p-6 text-slate-900">
      <div className="grid w-full max-w-5xl overflow-hidden rounded-[2rem] border border-white/20 bg-white shadow-[0_30px_80px_rgba(34,0,70,0.28)] md:grid-cols-2">
        <section className="relative hidden overflow-hidden bg-[linear-gradient(180deg,_#4f0fb0_0%,_#2a005c_100%)] p-10 text-white md:block">
          <div className="absolute inset-0 bg-[radial-gradient(circle_at_top,_rgba(255,255,255,0.22),_transparent_40%)]" />
          <div className="relative">
            <BrandMark />
            <div className="mt-16 max-w-sm">
              <p className="text-sm font-semibold uppercase tracking-[0.24em] text-[#f5deff]">The calm center of your stall</p>
              <h1 className="mt-5 text-4xl font-black leading-tight text-[#fff8ea]">Run every stall from one clear workspace.</h1>
              <p className="mt-5 text-base leading-7 text-[#f6ebff]">
                A clear owner workspace built around the Coolerz Ice Cream brand.
              </p>
            </div>
            <div className="mt-14 rounded-3xl border border-white/15 bg-white/10 p-4 shadow-2xl shadow-black/10">
              <img
                alt="Coolerz Ice Cream logo preview"
                className="aspect-square w-full rounded-[1.5rem] object-cover"
                src={brandLogo}
              />
            </div>
          </div>
        </section>

        <form className="bg-[linear-gradient(180deg,_#fffdf8_0%,_#f9f3ff_100%)] p-7 sm:p-10" onSubmit={submit}>
          <div className="md:hidden">
            <BrandMark compact />
          </div>
          <p className="mt-6 text-sm font-semibold uppercase tracking-[0.2em] text-[#5a1bb0] md:hidden">Coolerz IMS</p>
          <h2 className="mt-3 text-3xl font-black tracking-tight text-[#220046]">Owner sign in</h2>
          <p className="mt-2 max-w-md text-slate-500">Owners and system administrators can use this dashboard.</p>

          <label className="mt-7 block text-sm font-medium text-[#39235f]">
            Email
            <input
              className="mt-1 w-full rounded-xl border border-[#dfd4f3] bg-white px-3 py-3 text-sm outline-none transition focus:border-violet-500 focus:ring-2 focus:ring-[#eadcff]"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="username"
              required
            />
          </label>

          <label className="mt-4 block text-sm font-medium text-[#39235f]">
            Password
            <input
              className="mt-1 w-full rounded-xl border border-[#dfd4f3] bg-white px-3 py-3 text-sm outline-none transition focus:border-violet-500 focus:ring-2 focus:ring-[#eadcff]"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </label>

          {error && <Notice>{error}</Notice>}

          <Button className="mt-6 w-full py-3" disabled={isLoading}>
            {isLoading ? 'Signing in…' : 'Sign in'}
          </Button>

          <p className="mt-4 text-xs leading-5 text-slate-500">
            This dashboard uses the shop's custom password login and a short-lived Supabase session token.
          </p>
        </form>
      </div>
    </main>
  )
}

function MenuIcon({ open = false }: { open?: boolean }) {
  return open ? (
    <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
    </svg>
  ) : (
    <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
      <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
    </svg>
  )
}

function Sidebar({ activeView, onNavigate, onSignOut, session, open, onClose }: { activeView: View; onNavigate: (view: View) => void; onSignOut: () => void; session: AppSession; open: boolean; onClose: () => void }) {
  return (
    <>
      <button
        aria-label="Close navigation"
        className={`fixed inset-0 z-40 bg-[#18002f]/55 backdrop-blur-[2px] transition-opacity lg:hidden ${open ? 'opacity-100' : 'pointer-events-none opacity-0'}`}
        onClick={onClose}
        type="button"
      />
      <aside className={`fixed inset-y-0 left-0 z-50 flex w-[min(86vw,18rem)] shrink-0 flex-col bg-[linear-gradient(180deg,_#220046_0%,_#3a007a_100%)] text-white shadow-2xl transition-transform duration-300 ease-out lg:static lg:z-auto lg:min-h-screen lg:w-72 lg:translate-x-0 lg:shadow-none ${open ? 'translate-x-0' : '-translate-x-full'}`}>
      <div className="flex items-center justify-between p-5">
        <BrandMark />
        <button aria-label="Close navigation" className="rounded-lg p-2 text-[#f5deff] transition hover:bg-white/10 hover:text-white lg:hidden" onClick={onClose} type="button">
          <MenuIcon open />
        </button>
      </div>

      <nav className="flex flex-1 flex-col gap-5 overflow-y-auto px-3 pb-4">
        {navGroups.map((group) => (
          <div key={group.label}>
            <p className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-widest text-[#cfb5ff]">{group.label}</p>
            <div>
              {group.items.map((view) => (
                <button
                  key={view}
                  className={`mb-1 block min-h-11 w-full rounded-xl px-3 py-2 text-left text-sm transition ${
                    activeView === view
                      ? 'bg-[#f5d68c] font-semibold text-[#220046]'
                      : 'text-[#efe1ff] hover:bg-white/10 hover:text-white'
                  }`}
                  onClick={() => { onNavigate(view); onClose() }}
                  type="button"
                >
                  {viewLabels[view]}
                </button>
              ))}
            </div>
          </div>
        ))}
      </nav>

      <div className="border-t border-white/10 p-4">
        <p className="truncate text-sm font-medium">{session.displayName}</p>
        <p className="mt-1 text-xs text-[#d9c2ff]">
          {session.role === 'system_admin' ? 'System admin' : 'Owner'}
        </p>
        <button className="mt-4 text-sm font-semibold text-[#f5d68c] hover:text-white" onClick={onSignOut} type="button">
          Sign out
        </button>
      </div>
      </aside>
    </>
  )
}

function Workspace({ session, onSignOut }: { session: AppSession; onSignOut: () => void }) {
  const [view, setView] = useState<View>('overview')
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [data, setData] = useState<WorkspaceData | null>(null)
  const [stalls, setStalls] = useState<Stall[]>([])
  const [selectedStallId, setSelectedStallId] = useState(session.stallId)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null)
  const refreshInFlight = useRef<Promise<void> | null>(null)
  const client = useMemo(() => createSupabaseClient(session.token), [session.token])

  const refreshWorkspace = useCallback((silent = false): Promise<void> => {
    if (refreshInFlight.current) return refreshInFlight.current

    if (!silent) {
      setLoading(true)
      setError('')
    }

    const request = (async () => {
      try {
        const accessibleStalls = await listAccessibleStalls(client)
        if (accessibleStalls.length === 0) throw new Error('No stall is assigned to this account.')
        setStalls(accessibleStalls)
        const targetStallId = accessibleStalls.some((stall) => stall.id === selectedStallId)
          ? selectedStallId
          : accessibleStalls[0].id
        if (targetStallId !== selectedStallId) setSelectedStallId(targetStallId)
        setData(await loadWorkspace(client, targetStallId))
        setLastUpdatedAt(new Date())
        setError('')
      } catch (caughtError) {
        setError(caughtError instanceof Error ? caughtError.message : 'Unable to load dashboard data.')
      } finally {
        if (!silent) setLoading(false)
      }
    })()

    refreshInFlight.current = request
    void request.finally(() => {
      if (refreshInFlight.current === request) refreshInFlight.current = null
    })
    return request
  }, [client, selectedStallId])

  const refresh = useCallback(() => refreshWorkspace(false), [refreshWorkspace])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    return startWorkspaceAutoRefresh(() => refreshWorkspace(true))
  }, [refreshWorkspace])

  useEffect(() => {
    if (!sidebarOpen) return
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setSidebarOpen(false)
    }
    document.addEventListener('keydown', closeOnEscape)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', closeOnEscape)
      document.body.style.overflow = ''
    }
  }, [sidebarOpen])

  if (loading && !data) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[radial-gradient(circle_at_top,_#fff8ea,_#f2e7ff)] p-6">
        <LoadingState label="Loading your stall workspace…" />
      </div>
    )
  }

  if (!data) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-[radial-gradient(circle_at_top,_#fff8ea,_#f2e7ff)] p-6">
        <div className="w-full max-w-lg space-y-4">
          <Notice>{error || 'Unable to load the workspace.'}</Notice>
          <div className="flex gap-3">
            <Button onClick={() => void refresh()}>Try again</Button>
            <Button variant="ghost" onClick={onSignOut}>Sign out</Button>
          </div>
        </div>
      </main>
    )
  }

  const screenProps = { client, data, onRefresh: refresh, onError: setError, role: session.role, stalls }
  const screen =
    view === 'overview' ? (
      <OverviewScreen {...screenProps} onNavigate={(nextView) => setView(nextView as View)} />
    ) : view === 'stall' ? (
      <StallScreen {...screenProps} />
    ) : view === 'staff' ? (
      <StaffScreen {...screenProps} />
    ) : view === 'products' ? (
      <ProductsScreen {...screenProps} />
    ) : view === 'receiving' ? (
      <ReceivingScreen {...screenProps} />
    ) : view === 'adjustments' ? (
      <AdjustmentsScreen {...screenProps} />
    ) : view === 'pricing' ? (
      <PricingScreen {...screenProps} />
    ) : view === 'transactions' ? (
      <TransactionsScreen {...screenProps} />
    ) : view === 'days' ? (
      <OperatingDaysScreen {...screenProps} />
    ) : (
      <ReportsScreen {...screenProps} />
    )

  return (
    <div className="min-h-screen bg-[linear-gradient(180deg,_#fbf6ff_0%,_#fffdf8_100%)] text-slate-900 lg:flex">
      <Sidebar activeView={view} onNavigate={setView} onSignOut={onSignOut} session={session} open={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      <main className="min-w-0 flex-1 overflow-x-hidden">
        <header className="border-b border-[#eadcff] bg-white/85 px-4 py-4 backdrop-blur sm:px-8 sm:py-6">
          <div className="mx-auto max-w-7xl">
            <div className="flex items-start gap-3">
              <button
                aria-expanded={sidebarOpen}
                aria-label={sidebarOpen ? 'Close navigation' : 'Open navigation'}
                className="mt-0.5 inline-flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-xl border border-[#eadcff] bg-white text-[#4b2a7a] shadow-sm transition hover:border-[#caa8ff] hover:bg-[#fbf7ff] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-500 lg:hidden"
                onClick={() => setSidebarOpen((isOpen) => !isOpen)}
                type="button"
              >
                <MenuIcon open={sidebarOpen} />
              </button>
              <div className="min-w-0 flex-1">
                <p className="truncate text-xs font-semibold uppercase tracking-[0.16em] text-[#5a1bb0] sm:text-sm sm:tracking-[0.18em]">
              {data.stall?.name ?? 'Stall workspace'}
                </p>
                <div className="mt-1 flex flex-wrap items-center justify-between gap-2">
                  <h1 className="text-2xl font-black tracking-tight text-[#220046] sm:text-3xl">{viewLabels[view]}</h1>
                  {stalls.length > 1 && (
                    <Select aria-label="Active stall" className="mt-0 min-w-48" value={selectedStallId} onChange={(event) => setSelectedStallId(event.target.value)}>
                      {stalls.map((stall) => <option key={stall.id} value={stall.id}>{stall.name}</option>)}
                    </Select>
                  )}
                  <p className="hidden text-sm text-slate-500 sm:block">
                    Live updates on{lastUpdatedAt ? ` · updated ${lastUpdatedAt.toLocaleTimeString()}` : ''}
                  </p>
                </div>
              </div>
            </div>
          </div>
        </header>

        <div className="mx-auto w-full max-w-7xl space-y-4 p-4 sm:p-8">
          {error && (
            <div className="flex items-start justify-between gap-3">
              <Notice>{error}</Notice>
              <button className="px-2 text-slate-400" onClick={() => setError('')} aria-label="Dismiss error" type="button">
                ×
              </button>
            </div>
          )}
          {loading && data && <p className="text-xs text-slate-400">Refreshing workspace…</p>}
          {screen}
        </div>
      </main>
    </div>
  )
}

export function App() {
  const [session, setSession] = useState<AppSession | null>(() => getStoredSession())

  useEffect(() => {
    if (!session) return

    const timeoutId = window.setTimeout(() => {
      signOut()
      setSession(null)
    }, Math.max(0, Date.parse(session.expiresAt) - Date.now()))

    return () => window.clearTimeout(timeoutId)
  }, [session])

  if (!session) {
    return <LoginScreen onSignedIn={setSession} />
  }

  if (session.role === 'cashier') {
    return (
      <main className="flex min-h-screen items-center justify-center bg-[radial-gradient(circle_at_top,_#fff8ea,_#f2e7ff)] p-6">
        <div className="max-w-md space-y-4 rounded-3xl border border-[#eadcff] bg-white p-8 text-center shadow-lg">
          <img alt="Coolerz Ice Cream logo" className="mx-auto h-20 w-20 rounded-2xl object-cover shadow-md" src={brandLogo} />
          <h1 className="text-2xl font-black text-[#220046]">Owner access required</h1>
          <p className="text-slate-600">Cashiers use the activated Android POS. This dashboard is for Owners and System admins.</p>
          <Button onClick={() => { signOut(); setSession(null) }}>Sign out</Button>
        </div>
      </main>
    )
  }

  return <Workspace session={session} onSignOut={() => { signOut(); setSession(null) }} />
}
