import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { canAccessWebView, getDefaultView, getNavigation, getViewLabel, type WebView } from './lib/access'
import { listAccessibleStalls, loadWorkspace } from './lib/api'
import { appEnvironment } from './lib/supabase'
import { startWorkspaceAutoRefresh } from './lib/live-sync'
import { getStoredSession, signIn, signOut, type AppSession } from './lib/session'
import { createSupabaseClient } from './lib/supabase'
import type { Stall, WorkspaceData } from './lib/types'
import { Button, ConfirmationDialog, LoadingState, Notice, Select } from './components/ui'
import {
  AdjustmentsScreen,
  OwnerDashboardScreen,
  OperatingDaysScreen,
  ProductPerformanceScreen,
  SystemAdminOverviewScreen,
  OverviewScreen,
  ProductsScreen,
  PricingScreen,
  ReceivingScreen,
  ReportsScreen,
  StallScreen,
  StaffScreen,
  TransactionsScreen,
  DailyCloseScreen,
  DataResetScreen,
} from './screens'
import './styles.css'

const brandLogo = '/branding/coolerz-icecream-logo.png'

function BrandMark({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`flex items-center gap-3 ${compact ? '' : 'rounded-2xl border border-white/15 bg-white/8 px-4 py-3 shadow-lg shadow-black/15 backdrop-blur'}`}>
      <img
        alt="Coolerz Ice Cream logo"
        className={`${compact ? 'h-12 w-12 rounded-2xl border border-[#eadcff] bg-white object-cover shadow-md' : 'h-16 w-16 rounded-2xl border border-white/20 bg-white object-cover shadow-md'}`}
        src={brandLogo}
      />
      <div>
        <p className={`text-xs font-semibold uppercase tracking-[0.28em] ${compact ? 'text-[#5a1bb0]' : 'text-[#f4dcff]'}`}>Coolerz IMS</p>
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
    <main className="flex min-h-screen items-center justify-center bg-[#f7f5f9] p-4 text-slate-900 sm:p-6">
        <form className="w-full max-w-md rounded-2xl border border-[#e6deed] bg-white p-6 shadow-[0_18px_50px_rgba(34,0,70,0.10)] sm:p-8" onSubmit={submit}>
          <div className="flex justify-center"><BrandMark compact /></div>
          <h2 className="mt-6 text-center text-2xl font-black tracking-tight text-[#220046]">Management sign in</h2>
          <p className="mt-2 text-center text-slate-500">Owners and system administrators can use this dashboard.</p>

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

function SidebarIcon({ action }: { action: 'hide' | 'show' }) {
  return (
    <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.8">
      <rect x="3" y="3" width="18" height="18" rx="2.5" />
      <path strokeLinecap="round" d="M9 3v18" />
      <path strokeLinecap="round" strokeLinejoin="round" d={action === 'hide' ? 'm16 9-3 3 3 3' : 'm14 9 3 3-3 3'} />
    </svg>
  )
}

function SignOutIcon() {
  return <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M10 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h5M14 7l5 5-5 5m5-5H8" /></svg>
}

function NavigationIcon({ view }: { view: WebView }) {
  const common = {
    'aria-hidden': true,
    className: 'h-5 w-5 shrink-0',
    fill: 'none',
    viewBox: '0 0 24 24',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  }

  switch (view) {
    case 'admin':
      return <svg {...common}><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /></svg>
    case 'overview':
      return <svg {...common}><path d="M4 19V9m6 10V5m6 14v-7m4 7H2" /></svg>
    case 'stall':
      return <svg {...common}><path d="M3 9h18l-2-5H5L3 9Z" /><path d="M5 9v11h14V9M9 20v-6h6v6" /><path d="M3 9a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0" /></svg>
    case 'staff':
      return <svg {...common}><circle cx="9" cy="8" r="3" /><path d="M3.5 20v-1.5A4.5 4.5 0 0 1 8 14h2a4.5 4.5 0 0 1 4.5 4.5V20M16 5.5a3 3 0 0 1 0 5.8M17 14a4 4 0 0 1 3.5 4v2" /></svg>
    case 'dataReset':
      return <svg {...common}><path d="M4 7h16M9 7V4h6v3m3 0-1 13H7L6 7" /><path d="M10 11v5m4-5v5" /></svg>
    case 'products':
      return <svg {...common}><path d="m4 7 8-4 8 4-8 4-8-4Z" /><path d="m4 7 8 4 8-4v10l-8 4-8-4V7Z" /><path d="M12 11v10" /></svg>
    case 'receiving':
      return <svg {...common}><path d="M12 3v11m0 0 4-4m-4 4-4-4" /><path d="M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4" /></svg>
    case 'adjustments':
      return <svg {...common}><path d="M4 6h7m4 0h5M4 12h2m4 0h10M4 18h9m4 0h3" /><circle cx="13" cy="6" r="2" /><circle cx="8" cy="12" r="2" /><circle cx="15" cy="18" r="2" /></svg>
    case 'pricing':
      return <svg {...common}><path d="M4 4h7l9 9-7 7-9-9V4Z" /><circle cx="8" cy="8" r="1.25" /><path d="m12 10 3 3" /></svg>
    case 'transactions':
      return <svg {...common}><path d="M6 3h12v18l-3-2-3 2-3-2-3 2V3Z" /><path d="M9 8h6M9 12h6M9 16h3" /></svg>
    case 'reports':
      return <svg {...common}><path d="M4 20V4m0 16h16" /><path d="m7 15 4-4 3 2 5-6" /></svg>
    case 'productReport':
      return <svg {...common}><path d="M8 4h8v3a4 4 0 0 1-8 0V4Z" /><path d="M8 6H4v1a4 4 0 0 0 4 4m8-5h4v1a4 4 0 0 1-4 4M12 11v5m-4 4h8m-6-4h4" /></svg>
    case 'days':
      return <svg {...common}><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 10h18" /><path d="m8 15 2 2 5-5" /></svg>
    case 'dailyClose':
      return <svg {...common}><path d="M6 3h12v18H6z" /><path d="M9 7h6M9 11h6M9 15h3" /><path d="m14 17 1.5 1.5L19 15" /></svg>
  }
}

function EyeIcon({ visible }: { visible: boolean }) {
  return visible ? (
    <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.8">
      <path strokeLinecap="round" strokeLinejoin="round" d="M2.8 12s3.4-6 9.2-6 9.2 6 9.2 6-3.4 6-9.2 6-9.2-6-9.2-6Z" />
      <circle cx="12" cy="12" r="2.5" />
    </svg>
  ) : (
    <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.8">
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 3l18 18M10.6 6.1A9.8 9.8 0 0 1 12 6c5.8 0 9.2 6 9.2 6a15 15 0 0 1-2.7 3.4M6.2 6.2C4 7.7 2.8 12 2.8 12s3.4 6 9.2 6c1.3 0 2.5-.3 3.5-.8M9.9 9.9a3 3 0 0 0 4.2 4.2" />
    </svg>
  )
}

function SignOutDialog({ onCancel, onConfirm }: { onCancel: () => void; onConfirm: () => void }) {
  return <ConfirmationDialog confirmLabel="Sign out" description="You will need your email and password to open the dashboard again." onCancel={onCancel} onConfirm={onConfirm} title="Sign out?" tone="primary" />
}

function Sidebar({ activeView, onNavigate, onSignOut, session, open, collapsed, onClose, onToggle }: { activeView: WebView; onNavigate: (view: WebView) => void; onSignOut: () => void; session: AppSession; open: boolean; collapsed: boolean; onClose: () => void; onToggle: () => void }) {
  const navigation = getNavigation(session.role)
  const [tooltip, setTooltip] = useState<{ label: string; left: number; top: number } | null>(null)
  const showTooltip = (element: HTMLElement, label: string) => {
    if (!collapsed || !window.matchMedia('(min-width: 1024px)').matches) return
    const bounds = element.getBoundingClientRect()
    setTooltip({ label, left: bounds.right + 12, top: bounds.top + bounds.height / 2 })
  }
  const hideTooltip = () => setTooltip(null)
  return (
    <>
      <button
        aria-label="Close navigation"
        className={`fixed inset-0 z-40 bg-[#18002f]/55 backdrop-blur-[2px] transition-opacity lg:hidden ${open ? 'opacity-100' : 'pointer-events-none opacity-0'}`}
        onClick={onClose}
        type="button"
      />
      <aside aria-label={session.role === 'owner' ? 'Owner monitoring navigation' : 'System administration navigation'} className={`fixed inset-y-0 left-0 z-50 flex h-dvh w-[min(86vw,18rem)] shrink-0 flex-col overflow-x-clip bg-[linear-gradient(180deg,_#220046_0%,_#3a007a_100%)] text-white shadow-2xl transition-transform duration-300 ease-out lg:sticky lg:bottom-auto lg:top-0 lg:z-auto lg:h-screen lg:min-h-0 lg:self-start lg:translate-x-0 lg:shadow-none lg:transition-[width] lg:duration-200 lg:ease-in-out ${open ? 'translate-x-0' : '-translate-x-full'} ${collapsed ? 'lg:w-[72px]' : 'lg:w-72'}`}>
        <div className={`flex shrink-0 items-center justify-between gap-2 pb-5 pt-[max(1.25rem,env(safe-area-inset-top))] ${collapsed ? 'px-5 lg:flex-col lg:px-3' : 'px-5'}`}>
          <div className={collapsed ? 'lg:hidden' : ''}><BrandMark /></div>
          {collapsed && <div aria-label="Coolerz IMS" className="hidden h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-white shadow-md lg:flex" onMouseEnter={(event) => showTooltip(event.currentTarget, 'Coolerz IMS')} onMouseLeave={hideTooltip}>
            <img alt="" className="h-10 w-10 rounded-lg object-cover" src={brandLogo} />
          </div>}
          <button aria-label="Close navigation" className="rounded-lg p-2 text-[#f5deff] transition hover:bg-white/10 hover:text-white lg:hidden" onClick={onClose} type="button">
            <MenuIcon open />
          </button>
          <button aria-expanded={!collapsed} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} className="hidden min-h-10 min-w-10 items-center justify-center rounded-xl text-[#f5deff] transition hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#f5d68c] lg:inline-flex" onClick={() => { hideTooltip(); onToggle() }} onMouseEnter={(event) => showTooltip(event.currentTarget, 'Expand sidebar')} onMouseLeave={hideTooltip} onFocus={(event) => showTooltip(event.currentTarget, 'Expand sidebar')} onBlur={hideTooltip} title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} type="button">
            <SidebarIcon action={collapsed ? 'show' : 'hide'} />
          </button>
        </div>

        <nav className="sidebar-scrollbar min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain px-3 pb-4" aria-label="Primary navigation" onScroll={hideTooltip}>
          {navigation.map((group) => (
            <div key={group.label}>
              <p className={`px-3 pb-2 text-[11px] font-semibold uppercase tracking-widest text-[#cfb5ff] ${collapsed ? 'lg:sr-only' : ''}`}>{group.label}</p>
              <div className="space-y-1">
                {group.items.map((view) => (
                  <button
                    key={view}
                    aria-label={getViewLabel(session.role, view)}
                    aria-current={activeView === view ? 'page' : undefined}
                    className={`flex min-h-11 w-full items-center gap-3 rounded-xl px-3 py-2 text-left text-sm transition ${collapsed ? 'lg:justify-center lg:gap-0 lg:px-0' : ''} ${
                      activeView === view
                        ? 'bg-[#f5d68c] font-semibold text-[#220046]'
                        : 'text-[#efe1ff] hover:bg-white/10 hover:text-white'
                    }`}
                    onClick={() => { hideTooltip(); onNavigate(view); onClose() }}
                    onMouseEnter={(event) => showTooltip(event.currentTarget, getViewLabel(session.role, view))}
                    onMouseLeave={hideTooltip}
                    onFocus={(event) => showTooltip(event.currentTarget, getViewLabel(session.role, view))}
                    onBlur={hideTooltip}
                    type="button"
                  >
                    <NavigationIcon view={view} />
                    <span className={collapsed ? 'lg:sr-only' : ''}>{getViewLabel(session.role, view)}</span>
                  </button>
                ))}
              </div>
            </div>
          ))}
        </nav>

        <div className={`shrink-0 border-t border-white/10 pb-[max(1rem,env(safe-area-inset-bottom))] pt-4 ${collapsed ? 'px-4 lg:px-3' : 'px-4'}`}>
          <div className={collapsed ? 'lg:hidden' : ''}>
            <p className="truncate text-sm font-medium">{session.displayName}</p>
            <p className="mt-1 text-xs text-[#d9c2ff]">{session.role === 'system_admin' ? 'System admin' : 'Owner'}</p>
            <button className="mt-4 text-sm font-semibold text-[#f5d68c] hover:text-white" onClick={onSignOut} type="button">Sign out</button>
          </div>
          {collapsed && <div className="hidden flex-col items-center gap-2 lg:flex">
            <div aria-label={session.displayName} className="flex h-11 w-11 items-center justify-center rounded-full bg-[#eadcff] text-sm font-bold text-[#220046]" onMouseEnter={(event) => showTooltip(event.currentTarget, session.displayName)} onMouseLeave={hideTooltip}>{session.displayName.trim().charAt(0).toUpperCase() || '?'}</div>
            <button aria-label="Sign out" className="flex h-11 w-11 items-center justify-center rounded-xl text-[#f5d68c] transition hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#f5d68c]" onClick={() => { hideTooltip(); onSignOut() }} onMouseEnter={(event) => showTooltip(event.currentTarget, 'Sign out')} onMouseLeave={hideTooltip} onFocus={(event) => showTooltip(event.currentTarget, 'Sign out')} onBlur={hideTooltip} type="button"><SignOutIcon /></button>
          </div>}
        </div>
      </aside>
      {collapsed && tooltip && createPortal(<div role="tooltip" className="pointer-events-none fixed z-[100] rounded-lg border border-[#eadcff] bg-white px-3 py-1.5 text-xs font-semibold text-[#220046] shadow-lg" style={{ left: tooltip.left, top: tooltip.top, transform: 'translateY(-50%)' }}>{tooltip.label}</div>, document.body)}
    </>
  )
}

function Workspace({ session, onSignOut }: { session: AppSession; onSignOut: () => void }) {
  const [view, setView] = useState<WebView>(() => getDefaultView(session.role))
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [data, setData] = useState<WorkspaceData | null>(null)
  const [stalls, setStalls] = useState<Stall[]>([])
  const [selectedStallId, setSelectedStallId] = useState(session.stallId)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null)
  const [amountsVisible, setAmountsVisible] = useState(true)
  const [showSignOutDialog, setShowSignOutDialog] = useState(false)
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

  const refresh = useCallback(async () => {
    // A save must fetch again after any auto-refresh that started before the
    // write; reusing that older request can leave the new product invisible.
    const previous = refreshInFlight.current
    if (previous) {
      await previous
      if (refreshInFlight.current === previous) refreshInFlight.current = null
    }
    await refreshWorkspace(false)
  }, [refreshWorkspace])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    if (!canAccessWebView(session.role, view)) setView(getDefaultView(session.role))
  }, [session.role, view])

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

  const screenProps = { client, data, onRefresh: refresh, onError: setError, stalls, amountsVisible,
    canManageOperatingDays: session.role === 'system_admin' || session.role === 'owner' }
  const screen =
    view === 'admin' && session.role === 'system_admin' ? (
      <SystemAdminOverviewScreen {...screenProps} onNavigate={setView} />
    ) : view === 'overview' ? (
      session.role === 'owner'
        ? <OwnerDashboardScreen {...screenProps} onNavigate={setView} />
        : <OverviewScreen {...screenProps} onNavigate={(nextView) => setView(nextView as WebView)} />
    ) : view === 'stall' ? (
      <StallScreen {...screenProps} />
    ) : view === 'staff' ? (
      <StaffScreen {...screenProps} />
    ) : view === 'dataReset' && session.role === 'system_admin' ? (
      <DataResetScreen {...screenProps} />
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
    ) : view === 'productReport' ? (
      <ProductPerformanceScreen {...screenProps} />
    ) : view === 'dailyClose' ? (
      <DailyCloseScreen {...screenProps} />
    ) : (
      <ReportsScreen {...screenProps} />
    )

  return (
    <div className="min-h-screen bg-[linear-gradient(180deg,_#fbf6ff_0%,_#fffdf8_100%)] text-slate-900 lg:flex">
      <Sidebar activeView={view} onNavigate={setView} onSignOut={() => setShowSignOutDialog(true)} session={session} open={sidebarOpen} collapsed={sidebarCollapsed} onClose={() => setSidebarOpen(false)} onToggle={() => setSidebarCollapsed((value) => !value)} />
      <main className="min-w-0 flex-1 overflow-x-clip">
        <header className="sticky top-0 z-30 border-b border-[#eadcff] bg-white/90 px-4 pb-4 pt-[max(1rem,env(safe-area-inset-top))] backdrop-blur sm:px-8 sm:py-6">
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
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:gap-5">
                  <div className="min-w-0">
                    <p className="truncate text-xs font-semibold uppercase tracking-[0.16em] text-[#5a1bb0] sm:text-sm sm:tracking-[0.18em]">{data.stall?.name ?? 'Stall workspace'}</p>
                    <h1 className="mt-1 text-xl font-black tracking-tight text-[#220046] min-[390px]:text-2xl sm:text-3xl">{getViewLabel(session.role, view)}</h1>
                    <p className="mt-1 text-xs text-slate-500 sm:text-sm">
                      Live updates on{lastUpdatedAt ? ` · updated ${lastUpdatedAt.toLocaleTimeString()}` : ''}
                    </p>
                    <span className={`mt-2 inline-flex rounded-full px-2.5 py-1 text-[11px] font-extrabold tracking-wide ${appEnvironment === 'production' ? 'bg-red-100 text-red-800 ring-1 ring-red-300' : 'bg-emerald-100 text-emerald-900 ring-1 ring-emerald-300'}`}>
                      {appEnvironment === 'production' ? 'PRODUCTION DATABASE' : 'DEVELOPMENT DATABASE'}
                    </span>
                  </div>
                  <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto sm:shrink-0 sm:justify-end">
                    {stalls.length > 1 && (
                      <div className="min-w-0 flex-1 sm:w-48 sm:flex-none">
                        <Select aria-label="Active stall" className="mt-0" value={selectedStallId} onChange={(event) => setSelectedStallId(event.target.value)}>
                          {stalls.map((stall) => <option key={stall.id} value={stall.id}>{stall.name}</option>)}
                        </Select>
                      </div>
                    )}
                    <button
                      aria-pressed={!amountsVisible}
                      className="inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-xl border border-[#dfd4f3] bg-white px-3 text-sm font-semibold text-[#4b2a7a] shadow-sm transition hover:border-[#caa8ff] hover:bg-[#f7f3fb]"
                      onClick={() => setAmountsVisible((visible) => !visible)}
                      title={amountsVisible ? 'Hide financial amounts' : 'Show financial amounts'}
                      type="button"
                    >
                      <EyeIcon visible={amountsVisible} />
                      <span>{amountsVisible ? 'Hide amounts' : 'Show amounts'}</span>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </header>

        <div className="mx-auto w-full max-w-7xl space-y-4 p-3 min-[390px]:p-4 sm:p-8">
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
      {showSignOutDialog && <SignOutDialog onCancel={() => setShowSignOutDialog(false)} onConfirm={onSignOut} />}
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
