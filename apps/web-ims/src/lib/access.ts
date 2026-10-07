import type { AppRole } from './types'

export type WebView =
  | 'admin'
  | 'overview'
  | 'stall'
  | 'staff'
  | 'dataReset'
  | 'products'
  | 'receiving'
  | 'adjustments'
  | 'pricing'
  | 'transactions'
  | 'reports'
  | 'productReport'
  | 'days'
  | 'dailyClose'

export type NavigationGroup = {
  label: string
  items: WebView[]
}

const commonOperations: NavigationGroup[] = [
  { label: 'Inventory', items: ['products', 'receiving', 'adjustments', 'pricing'] },
  { label: 'Sales', items: ['transactions', 'reports', 'productReport', 'dailyClose', 'days'] },
]

const navigationByRole: Record<'system_admin' | 'owner', NavigationGroup[]> = {
  system_admin: [
    { label: 'Administration', items: ['admin', 'stall', 'staff', 'dataReset'] },
    { label: 'Selected stall', items: ['overview'] },
    ...commonOperations,
  ],
  owner: [
    { label: 'Monitoring', items: ['overview', 'reports', 'productReport', 'dailyClose', 'days'] },
  ],
}

const baseLabels: Record<WebView, string> = {
  admin: 'System overview',
  overview: 'Overview',
  stall: 'Stall settings',
  staff: 'Staff & devices',
  dataReset: 'Data reset',
  products: 'Products',
  receiving: 'Receive stock',
  adjustments: 'Adjust inventory',
  pricing: 'Prices & conversions',
  transactions: 'Transactions',
  reports: 'Sales reports',
  productReport: 'Product performance',
  days: 'Operating days',
  dailyClose: 'Daily Close & Profit',
}

export function getNavigation(role: AppRole): NavigationGroup[] {
  return role === 'cashier' ? [] : navigationByRole[role]
}

export function getDefaultView(role: AppRole): WebView {
  return role === 'system_admin' ? 'admin' : 'overview'
}

export function canAccessWebView(role: AppRole, view: WebView): boolean {
  return getNavigation(role).some((group) => group.items.includes(view))
}

export function getViewLabel(role: AppRole, view: WebView): string {
  if (role === 'system_admin') {
    if (view === 'stall') return 'Stall administration'
    if (view === 'staff') return 'Users & access'
    if (view === 'overview') return 'Stall overview'
  }
  if (role === 'owner') {
    if (view === 'overview') return 'Dashboard'
    if (view === 'reports') return 'Sales analytics'
  }
  return baseLabels[view]
}
