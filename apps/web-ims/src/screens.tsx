import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type { DbClient } from './lib/api'
import { appEnvironment } from './lib/supabase'
import { addInventoryEntry, adminCloseOpenBusinessDay, archiveCategory, archiveProduct, authorizePosRecovery, createCategory, createDeviceActivation, createManagedStall, getOverheadForStall, getPosDeviceStatus, getStockByProduct, getTransactionReceiptItems, listManagedUsers, resetStockLevels, resetTransactionsAndRevenue, revokePendingPosActivation, reviewPosRecovery, reverseTransaction, saveManagedUser, saveProduct, saveProductWithRecipe, setOwnerStalls, setStockOnHand, updateStall } from './lib/api'
import type { PosDeviceStatus } from './lib/api'
import type { WebView } from './lib/access'
import { getDashboardMetrics, getDailyProfitReport, calculateDailyOverhead, DEFAULT_OVERHEAD_ITEMS, formatDateRangeLabel, getBusinessDateKey, getProductPerformance, getRevenueTrend, shiftDateKey } from './lib/dashboard'
import { downloadCsv } from './lib/export'
import { formatFinancialAmount, formatUnitCostAmount } from './lib/privacy'
import { getDailyReconciliation, recipeCost, unitCost } from './lib/recipes'
import { isStockable, productMatchesType, productTypeLabel, type ProductTypeFilter } from './lib/product-classification'
import { receivedBaseUnits, targetBaseUnits } from './lib/inventory-units'
import type { ManagedUser, OverheadItem, Product, Stall, TransactionItem, WorkspaceData } from './lib/types'
import { Badge, Button, ConfirmationDialog, DeleteIcon, EditIcon, EmptyState, Input, Notice, OverheadIcon, Panel, Select, Table, TableCell, TableHead, Textarea } from './components/ui'
import { HorizontalBarChart, RevenueTrendChart } from './components/charts'

const dateTime = new Intl.DateTimeFormat('en-PH', { dateStyle: 'medium', timeStyle: 'short' })

export type ScreenProps = {
  client: DbClient
  data: WorkspaceData
  onRefresh: () => Promise<void>
  onError: (message: string) => void
  stalls: Stall[]
  amountsVisible: boolean
  canManageOperatingDays: boolean
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'The operation could not be completed.'
}

export function OverviewScreen({ data, onNavigate, amountsVisible }: ScreenProps & { onNavigate: (view: string) => void }) {
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const today = new Date().toISOString().slice(0, 10)
  const metrics = getDashboardMetrics(data.products, data.inventory, data.transactions, today)
  const stock = metrics.stock
  const lowStock = data.products.filter((product) => metrics.lowStock.includes(product.id))

  const cards = [
    { label: 'Sales today', value: money(metrics.sales), detail: `${metrics.completedSales} completed sale${metrics.completedSales === 1 ? '' : 's'}`, action: () => onNavigate('reports') },
    { label: 'Low-stock items', value: String(lowStock.length), detail: lowStock.length ? 'Review stock levels' : 'Everything is above threshold', action: () => onNavigate('adjustments') },
    { label: 'Active products', value: String(data.products.filter((product) => product.is_sellable).length), detail: `${data.products.length} total catalog items`, action: () => onNavigate('products') },
    { label: 'Transactions', value: String(data.transactions.length), detail: 'Latest 1,000 records loaded', action: () => onNavigate('transactions') },
  ]

  return <div className="space-y-6"><div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{cards.map((card) => <button key={card.label} className="rounded-2xl border border-[#eadcff] bg-white p-5 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-[#caa8ff]" onClick={card.action}><p className="text-sm text-slate-500">{card.label}</p><p className="mt-2 text-2xl font-bold text-slate-900">{card.value}</p><p className="mt-1 text-xs text-slate-400">{card.detail}</p></button>)}</div><div className="grid gap-6 lg:grid-cols-[1.2fr_0.8fr]"><Panel title="Stock watchlist" description="Products at or below their configured threshold."><Table><TableHead><th className="px-3 py-3">Product</th><th className="px-3 py-3">On hand</th><th className="px-3 py-3">Threshold</th><th className="px-3 py-3">Status</th></TableHead><tbody>{lowStock.slice(0, 8).map((product) => { const onHand = stock[product.id] ?? 0; return <tr key={product.id} className="border-b border-slate-100"><TableCell><p className="font-medium">{product.name}</p><p className="text-xs text-slate-400">{product.sku}</p></TableCell><TableCell>{onHand.toLocaleString()} {product.unit}</TableCell><TableCell>{product.low_stock_threshold.toLocaleString()} {product.unit}</TableCell><TableCell><Badge tone={onHand <= 0 ? 'danger' : 'warning'}>{onHand <= 0 ? 'Out of stock' : 'Low stock'}</Badge></TableCell></tr> })}</tbody></Table>{lowStock.length === 0 && <EmptyState title="No stock alerts" description="All active products are above their configured thresholds." />}</Panel><Panel title="Quick actions" description="Common selected-stall operations."><div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-1">{[['Receive stock', 'receiving'], ['Adjust inventory', 'adjustments'], ['Edit prices', 'pricing'], ['Download sales report', 'reports']].map(([label, view]) => <Button key={view} variant="secondary" className="text-left" onClick={() => onNavigate(view)}>{label}</Button>)}</div></Panel></div></div>
}

export function OwnerDashboardScreen({ data, onNavigate, amountsVisible }: ScreenProps & { onNavigate: (view: WebView) => void }) {
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const today = getBusinessDateKey()
  const yesterday = shiftDateKey(today, -1)
  const weekStart = shiftDateKey(today, -6)
  const trend = useMemo(() => getRevenueTrend(data.transactions, weekStart, today), [data.transactions, today, weekStart])
  const todayPoint = trend.at(-1) ?? { revenue: 0, orders: 0 }
  const yesterdayRevenue = data.transactions
    .filter((transaction) => transaction.status === 'completed' && getBusinessDateKey(transaction.occurred_at) === yesterday)
    .reduce((sum, transaction) => sum + transaction.total_amount, 0)
  const weekRevenue = trend.reduce((sum, point) => sum + point.revenue, 0)
  const weekOrders = trend.reduce((sum, point) => sum + point.orders, 0)
  const averageSale = weekOrders > 0 ? weekRevenue / weekOrders : 0
  const change = yesterdayRevenue > 0 ? ((todayPoint.revenue - yesterdayRevenue) / yesterdayRevenue) * 100 : null
  const products = useMemo(
    () => getProductPerformance(data.transactions, data.transactionItems, weekStart, today),
    [data.transactionItems, data.transactions, today, weekStart],
  )
  const openDay = data.businessDays.find((day) => day.closed_at === null)
  const recentSales = data.transactions.slice(0, 5)

  return (
    <div className="space-y-5">
      <section className="overflow-hidden rounded-3xl bg-[linear-gradient(135deg,_#26004f_0%,_#6d28d9_62%,_#9b5cf6_100%)] p-5 text-white shadow-[0_22px_55px_rgba(70,20,140,0.24)] sm:p-7">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#f5d68c]">Live stall snapshot</p>
            <h2 className="mt-2 text-2xl font-black">{data.stall?.name ?? 'Your stall'}</h2>
            <p className="mt-2 text-sm text-[#eadcff]">Revenue and sales activity update automatically when the POS syncs.</p>
          </div>
          <Badge tone={openDay ? 'success' : 'neutral'}>{openDay ? 'Open now' : 'Currently closed'}</Badge>
        </div>
        <div className="mt-7">
          <p className="text-sm text-[#e7d9f8]">Today’s revenue</p>
          <p className="mt-1 text-4xl font-black tracking-tight text-[#fff8ea] sm:text-5xl">{money(todayPoint.revenue)}</p>
          <p className="mt-2 text-xs text-[#e7d9f8]">{todayPoint.orders} completed order{todayPoint.orders === 1 ? '' : 's'}{change === null ? '' : ` · ${change >= 0 ? '+' : ''}${change.toFixed(0)}% vs yesterday`}</p>
        </div>
      </section>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {[['Orders today', todayPoint.orders.toLocaleString(), 'Completed sales'], ['7-day revenue', money(weekRevenue), `${weekOrders} orders`], ['Average sale', money(averageSale), 'Last 7 days'], ['Top product', products[0]?.name ?? '—', products[0] ? `${products[0].unitsSold.toLocaleString()} units` : 'No sales yet']].map(([label, value, detail]) => <div className="min-w-0 rounded-2xl border border-[#eadcff] bg-white p-3 shadow-sm min-[390px]:p-4" key={label}><p className="text-xs font-semibold text-slate-500">{label}</p><p className="mt-2 break-words text-lg font-black leading-tight text-[#220046] min-[390px]:text-xl">{value}</p><p className="mt-1 text-xs text-slate-400">{detail}</p></div>)}
      </div>

      <Panel title="Sales trend" description="Revenue and completed orders over the last seven days." action={<Button variant="ghost" onClick={() => onNavigate('reports')}>View analytics</Button>}>
        <RevenueTrendChart points={trend.map((point) => ({ date: point.date, value: point.revenue, orders: point.orders }))} formatValue={(value) => money(value)} showRevenue={amountsVisible} />
      </Panel>

      <div className="grid gap-5 lg:grid-cols-2">
        <Panel title="Top products" description="Highest product revenue in the last seven days." action={<Button variant="ghost" onClick={() => onNavigate('productReport')}>Full report</Button>}>
          <HorizontalBarChart items={products.slice(0, 5).map((product) => ({ label: product.name, value: product.revenue, detail: `${product.unitsSold.toLocaleString()} units` }))} formatValue={(value) => money(value)} />
        </Panel>
        <Panel title="Recent sales" description="Latest POS activity after synchronization.">
          {recentSales.length === 0 ? <EmptyState title="No sales yet" description="Transactions will appear after the POS completes and syncs a sale." /> : <div className="divide-y divide-slate-100">{recentSales.map((transaction) => <div className="flex items-center justify-between gap-3 py-3 first:pt-0 last:pb-0" key={transaction.id}><div className="min-w-0"><p className="truncate text-sm font-semibold text-[#39235f]">{transaction.receipt_number}</p><p className="mt-0.5 text-xs text-slate-400">{dateTime.format(new Date(transaction.occurred_at))}</p></div><div className="text-right"><p className="text-sm font-bold text-[#220046]">{money(transaction.total_amount)}</p><Badge tone={transaction.status === 'completed' ? 'success' : 'warning'}>{transaction.status}</Badge></div></div>)}</div>}
        </Panel>
      </div>
    </div>
  )
}

export function SystemAdminOverviewScreen({ client, data, stalls, onError, onNavigate, amountsVisible }: ScreenProps & { onNavigate: (view: WebView) => void }) {
  const [directory, setDirectory] = useState<ManagedUser[]>([])
  const [loadingDirectory, setLoadingDirectory] = useState(true)
  const stallKey = stalls.map((stall) => stall.id).join(',')
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const today = getBusinessDateKey()
  const weekStart = shiftDateKey(today, -6)
  const revenueTrend = useMemo(() => getRevenueTrend(data.transactions, weekStart, today), [data.transactions, today, weekStart])

  useEffect(() => {
    let active = true
    const stallIds = stallKey ? stallKey.split(',') : []
    void Promise.all(stallIds.map((stallId) => listManagedUsers(client, stallId)))
      .then((usersByStall) => {
        if (!active) return
        const uniqueUsers = new Map(usersByStall.flat().map((user) => [user.id, user]))
        setDirectory([...uniqueUsers.values()])
      })
      .catch((error) => { if (active) onError(getErrorMessage(error)) })
      .finally(() => { if (active) setLoadingDirectory(false) })
    return () => { active = false }
  }, [client, onError, stallKey])

  const ownerCount = directory.filter((user) => user.role === 'owner').length
  const cashierCount = directory.filter((user) => user.role === 'cashier').length
  const cards = [
    { label: 'Active stalls', value: String(stalls.length), detail: 'System-wide access', view: 'stall' as WebView },
    { label: 'Owner accounts', value: loadingDirectory ? '…' : String(ownerCount), detail: 'Assign access by stall', view: 'staff' as WebView },
    { label: 'Cashier accounts', value: loadingDirectory ? '…' : String(cashierCount), detail: 'Managed within each stall', view: 'staff' as WebView },
    { label: 'Selected stall', value: data.stall?.code ?? '—', detail: data.stall?.name ?? 'No stall selected', view: 'overview' as WebView },
  ]

  return (
    <div className="space-y-6">
      <section className="overflow-hidden rounded-3xl bg-[linear-gradient(135deg,_#220046_0%,_#4f0fb0_100%)] p-6 text-white shadow-[0_20px_50px_rgba(60,0,112,0.2)] sm:p-8">
        <p className="text-xs font-bold uppercase tracking-[0.22em] text-[#f5d68c]">System administration</p>
        <h2 className="mt-3 text-2xl font-black sm:text-3xl">Control stalls and account access across the business.</h2>
        <p className="mt-3 max-w-3xl text-sm leading-6 text-[#eadcff]">This area is available only to the System Administrator. Use it to create stalls, create Owner accounts, and decide which stalls each Owner can monitor.</p>
        <div className="mt-5 flex flex-wrap gap-3">
          <Button onClick={() => onNavigate('stall')}>Manage stalls</Button>
          <Button variant="secondary" onClick={() => onNavigate('staff')}>Manage users & access</Button>
        </div>
      </section>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {cards.map((card) => <button key={card.label} className="rounded-2xl border border-[#eadcff] bg-white p-5 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-[#caa8ff]" onClick={() => onNavigate(card.view)}><p className="text-sm text-slate-500">{card.label}</p><p className="mt-2 text-2xl font-bold text-slate-900">{card.value}</p><p className="mt-1 text-xs text-slate-400">{card.detail}</p></button>)}
      </div>

      <Panel title="Selected stall sales" description={`Revenue and completed orders · ${formatDateRangeLabel(weekStart, today)}`} action={<Button variant="ghost" onClick={() => onNavigate('reports')}>Open sales reports</Button>}>
        <RevenueTrendChart points={revenueTrend.map((point) => ({ date: point.date, value: point.revenue, orders: point.orders }))} formatValue={money} showRevenue={amountsVisible} />
      </Panel>

      <div className="grid gap-6 lg:grid-cols-2">
        <Panel title="System Administrator access" description="Platform-level responsibilities across every stall.">
          <ul className="space-y-3 text-sm text-slate-600">
            {['View and select every active stall', 'Create stalls and maintain stall identity', 'Create Owner or Cashier accounts', 'Assign Owners to one or several stalls', 'Perform operational support for any stall'].map((item) => <li className="flex gap-3" key={item}><span className="mt-0.5 text-emerald-600">✓</span><span>{item}</span></li>)}
          </ul>
        </Panel>
        <Panel title="Owner monitoring" description="Owners receive a phone workspace scoped by stall assignment.">
          <ul className="space-y-3 text-sm text-slate-600">
            {['View revenue, profit, and sales activity for assigned stalls', 'Monitor product performance and operating days', 'Cannot change products, stock, prices, staff, POS devices, or stall settings', 'Cannot assign stalls or open another stall’s records'].map((item) => <li className="flex gap-3" key={item}><span className="mt-0.5 text-[#7c3aed]">•</span><span>{item}</span></li>)}
          </ul>
        </Panel>
      </div>
    </div>
  )
}

export function StallScreen({ client, data, onRefresh, onError, amountsVisible }: ScreenProps) {
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const stall = data.stall
  const [name, setName] = useState(stall?.name ?? '')
  const [code, setCode] = useState(stall?.code ?? '')
  const [overheadItems, setOverheadItems] = useState<OverheadItem[]>(() =>
    stall?.overhead_config?.length ? stall.overhead_config : DEFAULT_OVERHEAD_ITEMS,
  )
  const [message, setMessage] = useState('')
  const [saving, setSaving] = useState(false)
  const [newStallName, setNewStallName] = useState('')
  const [newStallCode, setNewStallCode] = useState('')
  const hasUnsavedChanges = useRef(false)
  const formStallId = useRef(stall?.id)
  const stallId = stall?.id
  const persistedName = stall?.name ?? ''
  const persistedCode = stall?.code ?? ''
  const persistedOverhead = JSON.stringify(getOverheadForStall(stall))

  // Polling creates new objects every few seconds. Sync persisted changes only,
  // and keep the administrator's draft intact while they are editing this stall.
  useEffect(() => {
    if (formStallId.current === stallId && hasUnsavedChanges.current) return
    formStallId.current = stallId
    hasUnsavedChanges.current = false
    setName(persistedName)
    setCode(persistedCode)
    setOverheadItems(JSON.parse(persistedOverhead) as OverheadItem[])
  }, [stallId, persistedName, persistedCode, persistedOverhead])

  const totalDailyOverhead = calculateDailyOverhead(overheadItems)

  function updateItem(index: number, field: keyof OverheadItem, value: string | number) {
    setOverheadItems((current) =>
      current.map((item, i) => (i === index ? { ...item, [field]: field === 'dailyRate' ? Number(value) || 0 : value } : item)),
    )
  }

  function addItem() {
    hasUnsavedChanges.current = true
    setOverheadItems((current) => [
      ...current,
      {
        key: `custom_${Date.now()}`,
        label: 'New expense item',
        dailyRate: 0,
        icon: 'custom',
        description: 'Daily operational cost',
      },
    ])
  }

  function removeItem(index: number) {
    if (overheadItems.length <= 1) {
      onError('You must keep at least one overhead expense item.')
      return
    }
    hasUnsavedChanges.current = true
    setOverheadItems((current) => current.filter((_, i) => i !== index))
  }

  function resetToDefaults() {
    hasUnsavedChanges.current = true
    setOverheadItems(DEFAULT_OVERHEAD_ITEMS)
    setMessage('Reset overhead to standard defaults (₱743.33/day). Remember to save.')
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setMessage('')
    if (!stall || name.trim().length < 2 || code.trim().length < 2) {
      setMessage('Enter a stall name and code with at least 2 characters.')
      return
    }
    setSaving(true)
    try {
      const updated = await updateStall(client, stall.id, {
        name: name.trim(),
        code: code.trim().toUpperCase(),
        overhead_config: overheadItems,
      })
      hasUnsavedChanges.current = false
      setName(updated.name)
      setCode(updated.code)
      setOverheadItems(getOverheadForStall(updated))
      setMessage('Stall settings and overhead saved successfully.')
      await onRefresh()
    } catch (error) {
      onError(getErrorMessage(error))
    } finally {
      setSaving(false)
    }
  }

  async function addStall(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      await createManagedStall(client, newStallName, newStallCode)
      setNewStallName('')
      setNewStallCode('')
      setMessage('Stall created. Create a Cashier and a POS activation code in Users & access. Assign an Owner there if they need monitoring access.')
      await onRefresh()
    } catch (error) {
      onError(getErrorMessage(error))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-6">
      <Panel title="Add a stall" description="System administrators can create another stall when the business expands.">
        <form className="grid gap-3 sm:grid-cols-[1fr_1fr_auto]" onSubmit={addStall}>
          <Input label="Stall name" value={newStallName} onChange={(event) => setNewStallName(event.target.value)} required />
          <Input label="Stall code" value={newStallCode} onChange={(event) => setNewStallCode(event.target.value)} required />
          <Button className="self-end" disabled={saving}>Create stall</Button>
        </form>
      </Panel>
      <Panel title="Selected stall settings" description="Maintain this stall or open its operational tools for support.">
        {!stall ? (
          <EmptyState title="No stall found" description="Create the initial stall in Supabase before using the dashboard." />
        ) : (
          <form onSubmit={submit} onChangeCapture={() => { hasUnsavedChanges.current = true }}>
            <fieldset className="min-w-0 space-y-6" disabled={saving}>
            <div className="grid max-w-xl gap-4 sm:grid-cols-2">
              <Input label="Stall name" value={name} onChange={(event) => setName(event.target.value)} required />
              <div>
                <Input label="Stall code" value={code} onChange={(event) => setCode(event.target.value)} required />
                <p className="mt-1 text-xs text-slate-500">Cashiers enter this code at every POS sign-in. It is not the one-time activation code.</p>
              </div>
            </div>

            <div className="border-t border-[#eadcff] pt-6">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h3 className="text-base font-bold text-[#220046]">Daily Operating Expenses (OPEX / Fixed Overhead)</h3>
                  <p className="text-xs text-slate-500">
                    Customize the exact daily amounts deducted for cashier wages, rent, utilities, or other overhead.
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button type="button" variant="ghost" onClick={resetToDefaults}>
                    Reset defaults
                  </Button>
                  <Button type="button" variant="secondary" onClick={addItem}>
                    + Add expense item
                  </Button>
                </div>
              </div>

              <div className="mt-4 grid gap-4 sm:grid-cols-2">
                {overheadItems.map((item, index) => (
                  <div key={item.key || index} className="rounded-2xl border border-[#eadcff] bg-[#fffdf8] p-4 shadow-xs">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex flex-1 items-center gap-2">
                        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[#f4ecff] text-[#5a1bb0]"><OverheadIcon kind={item.key} /></span>
                        <input
                          className="flex-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-sm font-semibold text-[#220046] focus:border-violet-500 focus:outline-none"
                          value={item.label}
                          onChange={(e) => updateItem(index, 'label', e.target.value)}
                          placeholder="Expense name"
                          required
                        />
                      </div>
                      <button
                        aria-label={`Remove ${item.label}`}
                        className="rounded-lg p-1.5 text-xs text-slate-400 hover:bg-red-50 hover:text-red-600"
                        onClick={() => removeItem(index)}
                        type="button"
                      >
                        ✕
                      </button>
                    </div>

                    <div className="mt-3 grid gap-2 sm:grid-cols-[1fr_1.5fr]">
                      <div>
                        <label className="block text-[11px] font-semibold text-[#5a1bb0]">Daily rate (₱)</label>
                        <input
                          className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-sm font-bold text-[#220046] focus:border-violet-500 focus:outline-none"
                          type="number"
                          min="0"
                          step="0.01"
                          value={item.dailyRate}
                          onChange={(e) => updateItem(index, 'dailyRate', e.target.value)}
                          required
                        />
                      </div>
                      <div>
                        <label className="block text-[11px] font-semibold text-slate-500">Description / Note</label>
                        <input
                          className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-600 focus:border-violet-500 focus:outline-none"
                          value={item.description ?? ''}
                          onChange={(e) => updateItem(index, 'description', e.target.value)}
                          placeholder="e.g. Daily wage / lease"
                        />
                      </div>
                    </div>
                  </div>
                ))}
              </div>

              <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-[linear-gradient(135deg,_#220046_0%,_#3a007a_100%)] p-4 text-white shadow-md">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-widest text-[#cfb5ff]">Configured Daily Fixed Overhead</p>
                  <p className="text-xs text-[#ebd8ff]">This total is dynamically subtracted from sales for each operating day</p>
                </div>
                <p className="text-2xl font-black text-[#f5d68c]">{money(totalDailyOverhead)} / day</p>
              </div>
            </div>

            <div className="flex items-center gap-3 border-t border-slate-100 pt-4">
              <Button disabled={saving}>{saving ? 'Saving changes…' : 'Save stall & overhead settings'}</Button>
              {message && <Notice tone={message.includes('successfully') ? 'success' : 'info'}>{message}</Notice>}
            </div>
            </fieldset>
          </form>
        )}
      </Panel>
    </div>
  )
}

type DataResetAction = 'transactions' | 'stock'

export function DataResetScreen({ client, data, onRefresh, onError, amountsVisible }: ScreenProps) {
  const [pendingAction, setPendingAction] = useState<DataResetAction>()
  const [confirmationText, setConfirmationText] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [resetError, setResetError] = useState('')
  const stock = getStockByProduct(data.inventory)
  const stockedProducts = data.products.filter((product) => Math.abs(stock[product.id] ?? 0) > 0.0005).length
  const transactionRevenue = data.transactions
    .filter((transaction) => transaction.status === 'completed')
    .reduce((sum, transaction) => sum + transaction.total_amount, 0)
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const stallName = data.stall?.name ?? 'the selected stall'

  function requestReset(action: DataResetAction) {
    setMessage('')
    setResetError('')
    setConfirmationText('')
    setPendingAction(action)
  }

  function closeDialog() {
    if (busy) return
    setPendingAction(undefined)
    setConfirmationText('')
    setResetError('')
  }

  async function confirmReset() {
    if (!pendingAction || confirmationText !== resetPhrase || !data.stall) return
    const action = pendingAction
    setResetError('')
    setBusy(true)
    try {
      const result = action === 'transactions'
        ? await resetTransactionsAndRevenue(client, data.stall.id)
        : await resetStockLevels(client, data.stall.id)
      await onRefresh()
      setPendingAction(undefined)
      setConfirmationText('')
      setMessage(action === 'transactions'
        ? `${result.affected_records} transaction${result.affected_records === 1 ? '' : 's'} and ${result.affected_operating_days ?? 0} operating day${result.affected_operating_days === 1 ? '' : 's'} reset successfully. Financial reports now exclude earlier inventory adjustments; stock balances and their audit trail remain unchanged.`
        : `${result.affected_records} stock balance${result.affected_records === 1 ? '' : 's'} reset to zero successfully.`)
    } catch (error) {
      const errorMessage = getErrorMessage(error)
      setResetError(errorMessage)
      onError(errorMessage)
    } finally {
      setBusy(false)
    }
  }

  const transactionReset = pendingAction === 'transactions'
  const resetPhrase = appEnvironment === 'production' && data.stall ? `RESET ${data.stall.code.toUpperCase()}` : 'RESET'
  return <div className="space-y-6">
    <section className="rounded-3xl border border-red-200 bg-[linear-gradient(135deg,_#fff8f8_0%,_#fffdf8_100%)] p-6 shadow-sm sm:p-8">
      <p className="text-xs font-bold uppercase tracking-[0.2em] text-red-700">System administrator only</p>
      <h2 className="mt-2 text-2xl font-black text-[#220046]">Data reset</h2>
      <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-600">Reset operational data for <strong>{stallName}</strong>. These actions are protected, confirmed, and recorded in the security audit log.</p>
      {appEnvironment === 'production' && <p className="mt-4 rounded-xl border-2 border-red-400 bg-red-100 px-4 py-3 text-sm font-extrabold text-red-900">PRODUCTION DATABASE — changes here affect live business data.</p>}
    </section>

    {message && <Notice tone="success">{message}</Notice>}

    <div className="grid gap-5 lg:grid-cols-2">
      <Panel title="Sales and operating history" description="Clear the selected stall’s completed operating history so sales and their related daily financial reports start fresh.">
        <div className="space-y-5">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            <div className="rounded-2xl bg-[#f7f1ff] p-4"><p className="text-xs font-semibold text-slate-500">Transactions</p><p className="mt-2 text-2xl font-black text-[#220046]">{data.transactions.length.toLocaleString()}</p></div>
            <div className="rounded-2xl bg-[#f7f1ff] p-4"><p className="text-xs font-semibold text-slate-500">Completed revenue</p><p className="mt-2 text-2xl font-black text-[#220046]">{money(transactionRevenue)}</p></div>
            <div className="col-span-2 rounded-2xl bg-[#f7f1ff] p-4 sm:col-span-1"><p className="text-xs font-semibold text-slate-500">Operating days</p><p className="mt-2 text-2xl font-black text-[#220046]">{data.businessDays.length.toLocaleString()}</p></div>
          </div>
          <Notice tone="info"><strong>Close the operating day on every active POS first.</strong> The reset also clears a stale cloud copy that may still appear open after a failed sync. Earlier inventory adjustments stop affecting financial reports, but products, stock balances, inventory audit entries, recipes, staff, and settings remain unchanged.</Notice>
          <Button variant="danger" onClick={() => requestReset('transactions')}><DeleteIcon />Reset sales & operating history</Button>
        </div>
      </Panel>

      <Panel title="Stock levels" description="Bring every current product balance in the selected stall to zero using audited adjustment entries.">
        <div className="space-y-5">
          <div className="rounded-2xl bg-[#f7f1ff] p-4"><p className="text-xs font-semibold text-slate-500">Products with stock</p><p className="mt-2 text-2xl font-black text-[#220046]">{stockedProducts.toLocaleString()}</p></div>
          <p className="text-sm leading-6 text-slate-500">Products, transactions, and the inventory audit trail remain available. Only current quantities are balanced to zero.</p>
          <Button variant="danger" onClick={() => requestReset('stock')}><DeleteIcon />Reset all stock levels</Button>
        </div>
      </Panel>
    </div>

    {pendingAction && <ConfirmationDialog
      busy={busy}
      confirmDisabled={confirmationText !== resetPhrase}
      confirmLabel={transactionReset ? 'Reset sales & operating history' : 'Reset all stock levels'}
      description={transactionReset
        ? `This clears transactions, revenue, product COGS, daily closings, operating days, operating-day overhead, and the financial-report effect of earlier inventory adjustments for ${stallName}. Confirm that every POS is closed before continuing. Products, stock quantities, and inventory audit entries will not change.`
        : `This creates balancing adjustments that set every current stock quantity in ${stallName} to zero. Transaction history will not change.`}
      onCancel={closeDialog}
      onConfirm={() => void confirmReset()}
      title={transactionReset ? 'Reset sales and operating history?' : 'Reset all stock levels?'}
    >
      {resetError && <div className="mb-4"><Notice>{resetError}</Notice></div>}
      <div className="rounded-2xl border border-red-200 bg-red-50 p-4">
        <label className="block text-sm font-semibold text-red-900">Type <span className="font-black">{resetPhrase}</span> to continue
          <input aria-label="Type RESET to confirm" autoComplete="off" className="mt-2 w-full rounded-xl border border-red-200 bg-white px-3 py-2 text-sm uppercase outline-none focus:border-red-500 focus:ring-2 focus:ring-red-100" onChange={(event) => setConfirmationText(event.target.value.toUpperCase())} value={confirmationText} />
        </label>
      </div>
    </ConfirmationDialog>}
  </div>
}

type ProductFormValues = { name: string; unit: string; category_id: string; sell_category: string; sale_price: string; cost_price: string; low_stock_threshold: string; pack_size: string; conversion_rate: string; is_sellable: boolean; product_type: Product['product_type'] | ''; base_unit: Product['base_unit'] }
const emptyProduct: ProductFormValues = { name: '', unit: 'piece', category_id: '', sell_category: '', sale_price: '0', cost_price: '0', low_stock_threshold: '0', pack_size: '1', conversion_rate: '1', is_sellable: false, product_type: '', base_unit: 'piece' }

function ProductFormFields({ form, setField, categories, sellCategories }: { form: ProductFormValues; setField: (field: keyof ProductFormValues, value: string | boolean) => void; categories: WorkspaceData['categories']; sellCategories: string[] }) {
  const stockItem = form.product_type === 'raw' || form.product_type === 'packaging'
  return <>
    <div className="grid gap-3 sm:grid-cols-2"><Input label="Product name" value={form.name} onChange={(event) => setField('name', event.target.value)} required autoFocus /><Select label="Classification" value={form.product_type} onChange={(event) => setField('product_type', event.target.value)} required><option value="" disabled>Choose classification</option><option value="sellable">Sellable menu item</option><option value="raw">Raw ingredient</option><option value="packaging">Packaging</option></Select></div>
    <div><Input label="POS category" list="pos-category-suggestions" maxLength={50} placeholder="e.g. Cup, Cone, Sundae" required={form.product_type === 'sellable'} disabled={form.product_type !== 'sellable'} value={form.sell_category} onChange={(event) => setField('sell_category', event.target.value)} /><datalist id="pos-category-suggestions">{sellCategories.map((category) => <option key={category} value={category} />)}</datalist><p className="mt-1 text-xs text-slate-500">{form.product_type === 'sellable' ? 'Groups this menu item in POS Sell after sync. Flavor is separate.' : 'Choose Sellable menu item to set its POS category.'}</p></div>
    <p className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-950">Only <strong>Raw ingredient</strong> and <strong>Packaging</strong> appear in Receive Stock. Sellable menu items use a recipe and are not received directly.</p>
    <p className="rounded-xl border border-[#eadcff] bg-[#fbf7ff] px-3 py-2 text-sm text-[#4b2a7a]"><span className="font-semibold">SKU:</span> Generated automatically when you save this product.</p>
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2"><Select label="Base unit" value={form.base_unit} onChange={(event) => { setField('base_unit', event.target.value); setField('unit', event.target.value) }}><option value="g">Gram (g)</option><option value="ml">Milliliter (ml)</option><option value="piece">Piece</option></Select><Select label="Flavor" value={form.category_id} onChange={(event) => setField('category_id', event.target.value)}><option value="">No flavor</option>{categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</Select></div>
    {stockItem ? <>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2"><Input label="Purchase cost" type="number" min="0" step="0.01" value={form.cost_price} onChange={(event) => setField('cost_price', event.target.value)} /><Input label={`Pack size (${form.base_unit})`} type="number" min="0.001" step="0.001" value={form.pack_size} onChange={(event) => setField('pack_size', event.target.value)} /></div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2"><Input label="Low-stock threshold" type="number" min="0" step="0.001" value={form.low_stock_threshold} onChange={(event) => setField('low_stock_threshold', event.target.value)} /><Input label="Conversion rate" hint="Usable units per pack" type="number" min="0.001" step="0.001" value={form.conversion_rate} onChange={(event) => setField('conversion_rate', event.target.value)} /></div>
    </> : form.product_type === 'sellable' ? <Input label="Sale price" type="number" min="0" step="0.01" value={form.sale_price} onChange={(event) => setField('sale_price', event.target.value)} /> : null}
  </>
}

export function StaffScreen({ client, data, onError, stalls }: ScreenProps) {
  const stallId = data.stall?.id ?? ''
  const [users, setUsers] = useState<ManagedUser[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [editingId, setEditingId] = useState<string>()
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [userRole, setUserRole] = useState<'owner' | 'cashier'>('cashier')
  const [password, setPassword] = useState('')
  const [isActive, setIsActive] = useState(true)
  const [assignedStalls, setAssignedStalls] = useState<string[]>(stallId ? [stallId] : [])
  const [deviceName, setDeviceName] = useState('Main stall POS')
  const [activationCode, setActivationCode] = useState('')
  const [activationExpiry, setActivationExpiry] = useState('')
  const [deviceStatus, setDeviceStatus] = useState<PosDeviceStatus | null>(null)
  const [recoveryReason, setRecoveryReason] = useState('')
  const [recoveryReview, setRecoveryReview] = useState('')
  const [message, setMessage] = useState('')

  const refreshUsers = useCallback(async () => {
    if (!stallId) return
    setLoading(true)
    try { setUsers(await listManagedUsers(client, stallId)) }
    catch (error) { onError(getErrorMessage(error)) }
    finally { setLoading(false) }
  }, [client, onError, stallId])

  useEffect(() => { void refreshUsers() }, [refreshUsers])

  const refreshDeviceStatus = useCallback(async () => {
    if (!stallId) { setDeviceStatus(null); return }
    try { setDeviceStatus(await getPosDeviceStatus(client, stallId)) }
    catch (error) { setDeviceStatus(null); onError(getErrorMessage(error)) }
  }, [client, onError, stallId])

  useEffect(() => { void refreshDeviceStatus() }, [refreshDeviceStatus])

  function resetForm() {
    setEditingId(undefined); setEmail(''); setDisplayName(''); setUserRole('cashier')
    setPassword(''); setIsActive(true); setAssignedStalls(stallId ? [stallId] : [])
  }

  useEffect(() => {
    setEditingId(undefined); setEmail(''); setDisplayName(''); setUserRole('cashier')
    setPassword(''); setIsActive(true); setAssignedStalls(stallId ? [stallId] : [])
    setActivationCode(''); setActivationExpiry(''); setDeviceStatus(null); setRecoveryReason(''); setRecoveryReview(''); setMessage('')
  }, [stallId])

  function editUser(user: ManagedUser) {
    setEditingId(user.id); setEmail(user.email); setDisplayName(user.display_name)
    setUserRole(user.role); setPassword(''); setIsActive(user.is_active)
    setAssignedStalls(user.stall_ids.length ? user.stall_ids : [user.stall_id])
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!stallId) return
    setSaving(true); setMessage('')
    try {
      const saved = await saveManagedUser(client, {
        stallId, email, displayName, role: userRole,
        password, userId: editingId, isActive,
      })
      if (userRole === 'owner') {
        await setOwnerStalls(client, saved.id, assignedStalls.length ? assignedStalls : [stallId])
      }
      setMessage(editingId ? 'Staff account updated.' : 'Staff account created.')
      resetForm(); await refreshUsers()
    } catch (error) { onError(getErrorMessage(error)) }
    finally { setSaving(false) }
  }

  const [savingDirectory, setSavingDirectory] = useState(false)
  async function createActivation() {
    if (!stallId) return
    setSavingDirectory(true); setActivationCode('')
    try {
      const result = await createDeviceActivation(client, stallId, deviceName)
      setActivationCode(result.activation_code)
      setActivationExpiry(result.expires_at)
      setMessage('A one-time activation code was created. Use it before the expiry shown below; redeeming it deactivates the previous POS for this stall.')
      await refreshDeviceStatus()
    } catch (error) { onError(getErrorMessage(error)) }
    finally { setSavingDirectory(false) }
  }

  async function revokeActivation() {
    if (!stallId) return
    setSavingDirectory(true)
    try {
      await revokePendingPosActivation(client, stallId)
      setActivationCode('')
      setActivationExpiry('')
      setMessage('The pending activation code was revoked.')
      await refreshDeviceStatus()
    } catch (error) { onError(getErrorMessage(error)) }
    finally { setSavingDirectory(false) }
  }

  async function recoverDevice() {
    if (!stallId || recoveryReason.trim().length < 10) return
    setSavingDirectory(true)
    try {
      await authorizePosRecovery(client, stallId, recoveryReason.trim())
      setRecoveryReason('')
      setMessage('Recovery authorized for 30 minutes. Create an activation code now. Work that never synced from the old phone cannot be restored.')
      await refreshDeviceStatus()
    } catch (error) { onError(getErrorMessage(error)) }
    finally { setSavingDirectory(false) }
  }

  async function submitRecoveryReview() {
    const incidentId = deviceStatus?.recovery_incident_id
    if (!incidentId || recoveryReview.trim().length < 10) return
    setSavingDirectory(true)
    try {
      await reviewPosRecovery(client, incidentId, recoveryReview.trim())
      setRecoveryReview('')
      setMessage('Recovery reconciliation recorded and saved to the security audit log.')
      await refreshDeviceStatus()
    } catch (error) { onError(getErrorMessage(error)) }
    finally { setSavingDirectory(false) }
  }

  const recoveryAuthorized = deviceStatus?.recovery_status === 'pending'
  const replacementPrepared = Boolean(deviceStatus?.transfer_ready_at && Date.now() - Date.parse(deviceStatus.transfer_ready_at) < 30 * 60 * 1000)
  const canCreateActivation = !deviceStatus?.active_device_id || recoveryAuthorized || (!deviceStatus?.open_business_date && replacementPrepared)

  return <div className="space-y-6">
    <Panel title="Users and stall access" description="Create Owners or Cashiers and control their stall access. System Administrator accounts remain database-managed.">
      <form className="grid gap-4 md:grid-cols-2" onSubmit={submit}>
        <Input label="Display name" value={displayName} onChange={(event) => setDisplayName(event.target.value)} required />
        <Input label="Email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
        <Select label="Role" value={userRole} onChange={(event) => setUserRole(event.target.value as 'owner' | 'cashier')}><option value="cashier">Cashier</option><option value="owner">Owner</option></Select>
        <Input label={editingId ? 'New password (optional)' : 'Temporary password'} type="password" minLength={8} value={password} onChange={(event) => setPassword(event.target.value)} required={!editingId} />
        <label className="flex items-center gap-2 text-sm text-[#39235f]"><input type="checkbox" checked={isActive} onChange={(event) => setIsActive(event.target.checked)} /> Active account</label>
        {userRole === 'owner' && <fieldset className="rounded-xl border border-[#eadcff] p-3 md:col-span-2"><legend className="px-1 text-sm font-semibold text-[#39235f]">Assigned stalls</legend>{stalls.map((stall) => <label className="mr-5 inline-flex items-center gap-2 text-sm" key={stall.id}><input type="checkbox" checked={assignedStalls.includes(stall.id)} onChange={(event) => setAssignedStalls((current) => event.target.checked ? [...new Set([...current, stall.id])] : current.filter((id) => id !== stall.id))} />{stall.name}</label>)}</fieldset>}
        <div className="flex gap-2 md:col-span-2"><Button disabled={saving}>{saving ? 'Saving…' : editingId ? 'Save account' : 'Create account'}</Button>{editingId && <Button type="button" variant="ghost" onClick={resetForm}>Cancel</Button>}</div>
        {message && <div className="md:col-span-2"><Notice tone="success">{message}</Notice></div>}
      </form>
      <div className="mt-6">{loading ? <p className="text-sm text-slate-500">Loading staff…</p> : users.length === 0 ? <EmptyState title="No staff accounts" description="Create the first account for this stall." /> : <Table><TableHead><th className="px-3 py-3">Name</th><th className="px-3 py-3">Role</th><th className="px-3 py-3">Status</th><th className="px-3 py-3" /></TableHead><tbody>{users.map((user) => <tr className="border-b border-slate-100" key={user.id}><TableCell><p className="font-medium">{user.display_name}</p><p className="text-xs text-slate-500">{user.email}</p></TableCell><TableCell>{user.role === 'owner' ? 'Owner' : 'Cashier'}</TableCell><TableCell><Badge tone={user.is_active ? 'success' : 'danger'}>{user.is_active ? 'Active' : 'Inactive'}</Badge></TableCell><TableCell><Button variant="ghost" onClick={() => editUser(user)}><EditIcon />Edit</Button></TableCell></tr>)}</tbody></Table>}</div>
    </Panel>
    <Panel title="Cashier POS activation" description="First create a Cashier above. Their POS sign-in uses the stall code, email, and password; this separate one-time code activates the phone.">
      <div className="mb-4 rounded-xl border border-[#eadcff] bg-[#f9f5ff] p-3 text-sm text-[#39235f]">
        {deviceStatus?.active_device_id ? <p>Active POS: <strong>{deviceStatus.active_device_name}</strong> · {deviceStatus.transfer_ready_at && Date.now() - Date.parse(deviceStatus.transfer_ready_at) < 30 * 60 * 1000 ? 'Prepared for replacement' : 'To replace it, close the day and use Prepare for replacement in its Device information screen.'}</p> : <p>No active POS is registered for this stall.</p>}
        {deviceStatus?.open_business_date && <p className="mt-1 text-amber-800">Operating day {deviceStatus.open_business_date} is open.{recoveryAuthorized ? ' The incident is authorized; the new POS will resume this server day for cash reconciliation.' : ' Close and sync it before planned replacement, or authorize lost POS recovery if the phone is unavailable.'}</p>}
        {deviceStatus?.recovery_status && <p className="mt-2 font-semibold text-amber-900">Lost POS recovery: {deviceStatus.recovery_status}{deviceStatus.recovery_business_date ? ` · ${deviceStatus.recovery_business_date}` : ''}{deviceStatus.recovery_known_sales !== null ? ` · ${formatFinancialAmount(deviceStatus.recovery_known_sales, true)} server-known sales` : ''}</p>}
        {deviceStatus?.recovery_status === 'activated' && !deviceStatus.open_business_date && <div className="mt-3 max-w-xl rounded-xl border border-amber-200 bg-amber-50 p-4">
          <p className="text-sm text-amber-950">Review the recovered day against counted cash and receipts, then record how the difference was reconciled.</p>
          <div className="mt-3"><Input label="Recovery reconciliation notes" value={recoveryReview} onChange={(event) => setRecoveryReview(event.target.value)} /></div>
          <Button className="mt-3" variant="ghost" disabled={savingDirectory || recoveryReview.trim().length < 10} onClick={() => void submitRecoveryReview()}>Record recovery review</Button>
        </div>}
        {deviceStatus?.pending_code_expires_at && <p className="mt-1">A pending code expires {dateTime.format(new Date(deviceStatus.pending_code_expires_at))}.</p>}
      </div>
      <div className="grid max-w-xl gap-3 sm:grid-cols-[1fr_auto]"><Input label="Device name" value={deviceName} onChange={(event) => setDeviceName(event.target.value)} /><Button className="self-end" disabled={savingDirectory || !canCreateActivation} onClick={() => void createActivation()}>{savingDirectory ? 'Creating…' : 'Create activation code'}</Button></div>
      {deviceStatus?.active_device_id && !deviceStatus.recovery_status && !(deviceStatus.transfer_ready_at && Date.now() - Date.parse(deviceStatus.transfer_ready_at) < 30 * 60 * 1000) && <div className="mt-4 max-w-xl rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">
        <p className="font-semibold">Old POS lost or unavailable?</p>
        <p className="mt-1">An administrator can authorize recovery even when its operating day is open. The replacement resumes the server day and shows server-known totals for cash reconciliation. Sales, deductions, and stock changes queued only on the lost phone cannot be recovered automatically. If app data is gone, managers can also close the open day from IMS → Operating days after counting cash; this records server-synced totals and disables the old POS. Record why the phone is unavailable; this is saved in the security audit log.</p>
        <div className="mt-3"><Input label="Recovery reason" value={recoveryReason} onChange={(event) => setRecoveryReason(event.target.value)} /></div>
        <Button className="mt-3" variant="ghost" disabled={savingDirectory || recoveryReason.trim().length < 10} onClick={() => void recoverDevice()}>Authorize recovery</Button>
      </div>}
      {deviceStatus?.pending_code_expires_at && <Button className="mt-3" variant="ghost" disabled={savingDirectory} onClick={() => void revokeActivation()}>Revoke pending code</Button>}
      {activationCode && <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-4"><p className="text-sm text-amber-800">Enter this code once on the Android POS:</p><p className="mt-1 font-mono text-2xl font-black tracking-widest text-amber-950">{activationCode}</p><p className="mt-1 text-xs text-amber-700">This code will not be shown again. It expires {dateTime.format(new Date(activationExpiry))}.</p></div>}
    </Panel>
  </div>
}

export function ProductsScreen({ client, data, onRefresh, onError, amountsVisible }: ScreenProps) {
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const unitMoney = (value: number) => formatUnitCostAmount(value, amountsVisible)
  const [form, setForm] = useState(emptyProduct)
  const [editingId, setEditingId] = useState<string>()
  const [productModalOpen, setProductModalOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState<ProductTypeFilter>('all')
  const [message, setMessage] = useState('')
  const [successMessage, setSuccessMessage] = useState('')
  const [categoryName, setCategoryName] = useState('')
  const [deletingCategoryId, setDeletingCategoryId] = useState<string>()
  const [deletingProductId, setDeletingProductId] = useState<string>()
  const [pendingDelete, setPendingDelete] = useState<{ kind: 'product' | 'flavor'; id: string; name: string }>()
  const [saving, setSaving] = useState(false)
  const [recipeLines, setRecipeLines] = useState<Array<{ ingredient_product_id: string; quantity: string }>>([])
  const sellCategories = [...new Set(['Cup', 'Cone', ...data.products.map((product) => product.sell_category?.trim() ?? '').filter(Boolean)])].sort((a, b) => a.localeCompare(b))
  const stock = getStockByProduct(data.inventory)
  const filtered = data.products.filter((product) => productMatchesType(product, typeFilter) && `${product.name} ${product.sku}`.toLowerCase().includes(search.toLowerCase()))
  function setField(field: keyof typeof emptyProduct, value: string | boolean) { setForm((current) => ({ ...current, [field]: value })) }
  function openAddProduct() { setEditingId(undefined); setForm(emptyProduct); setRecipeLines([]); setMessage(''); setProductModalOpen(true) }
  function edit(product: Product) { setEditingId(product.id); setForm({ name: product.name, unit: product.unit, category_id: product.category_id ?? '', sell_category: product.sell_category ?? data.categories.find((category) => category.id === product.category_id)?.name ?? '', sale_price: String(product.sale_price), cost_price: String(product.cost_price), low_stock_threshold: String(product.low_stock_threshold), pack_size: String(product.pack_size), conversion_rate: String(product.conversion_rate), is_sellable: product.is_sellable, product_type: product.product_type, base_unit: product.base_unit }); setRecipeLines(data.recipes.filter((recipe) => recipe.parent_product_id === product.id).map((recipe) => ({ ingredient_product_id: recipe.ingredient_product_id, quantity: String(recipe.quantity) }))); setMessage(''); setProductModalOpen(true) }
  const closeProductModal = useCallback(() => { if (saving) return; setProductModalOpen(false); setEditingId(undefined); setForm(emptyProduct); setMessage('') }, [saving])
  async function submit(event: FormEvent) {
    event.preventDefault(); setMessage('')
    if (!form.product_type) { setMessage('Choose whether this product is a sellable menu item, raw ingredient, or packaging.'); return }
    const values = { ...form, name: form.name.trim(), unit: form.unit.trim(), sell_category: form.sell_category.trim() }
    const posCategory = sellCategories.find((category) => category.toLocaleLowerCase() === values.sell_category.toLocaleLowerCase()) ?? values.sell_category
    const numbers = ['sale_price', 'cost_price', 'low_stock_threshold', 'pack_size', 'conversion_rate'].map((field) => Number(form[field as keyof typeof form]))
    if (!values.name || !values.unit || numbers.some((value) => !Number.isFinite(value) || value < 0) || numbers[3] <= 0 || numbers[4] <= 0) { setMessage('Complete all product fields with valid non-negative values. Pack size and conversion rate must be greater than zero.'); return }
    if (form.product_type === 'sellable' && !values.sell_category) { setMessage('Enter a POS category for this menu item.'); return }
    const recipe = form.product_type === 'sellable' ? recipeLines.filter((line) => line.ingredient_product_id).map((line) => ({ ingredient_product_id: line.ingredient_product_id, quantity: Number(line.quantity) })) : []
    if (recipe.some((line) => !Number.isFinite(line.quantity) || line.quantity <= 0)) { setMessage('Every recipe quantity must be greater than zero.'); return }
    const computedCost = form.product_type === 'sellable' ? recipeCost(recipe.map((line, index) => ({ ...line, id: String(index), stall_id: data.stall?.id ?? '', parent_product_id: editingId ?? '', updated_at: '' })), data.products) : numbers[1]
    const wasEditing = Boolean(editingId)
    setSaving(true)
    try {
      await saveProductWithRecipe(client, { stall_id: data.stall?.id ?? '', category_id: values.category_id || null, sell_category: form.product_type === 'sellable' ? posCategory : null, name: values.name, unit: values.base_unit, sale_price: numbers[0], cost_price: computedCost, low_stock_threshold: numbers[2], pack_size: numbers[3], conversion_rate: numbers[4], is_sellable: form.product_type === 'sellable', product_type: form.product_type, base_unit: form.base_unit }, recipe, editingId)
      await onRefresh()
      closeProductModal()
      setSuccessMessage(isStockable({ product_type: form.product_type })
        ? `Product successfully ${wasEditing ? 'updated' : 'created'}. It is available in Receive Stock.`
        : `Menu item successfully ${wasEditing ? 'updated' : 'created'}. Its ${posCategory} category will appear in POS Sell after sync.`)
    } catch (error) { onError(getErrorMessage(error)) } finally { setSaving(false) }
  }
  async function addNewCategory(event: FormEvent) {
    event.preventDefault()
    if (!categoryName.trim() || !data.stall) return
    try {
      await createCategory(client, { stall_id: data.stall.id, name: categoryName.trim(), sort_order: data.categories.length })
      setCategoryName('')
      await onRefresh()
      setSuccessMessage('Flavor successfully created.')
    } catch (error) { onError(getErrorMessage(error)) }
  }
  function deleteFlavor(categoryId: string, categoryNameToDelete: string) {
    const assignedProducts = data.products.filter((product) => product.category_id === categoryId)
    if (assignedProducts.length > 0) {
      onError(`Cannot delete ${categoryNameToDelete}. Remove this flavor from ${assignedProducts.length} product${assignedProducts.length === 1 ? '' : 's'} first.`)
      return
    }
    setPendingDelete({ kind: 'flavor', id: categoryId, name: categoryNameToDelete })
  }
  function deleteProduct(product: Product) {
    setPendingDelete({ kind: 'product', id: product.id, name: product.name })
  }
  async function confirmCatalogDelete() {
    if (!pendingDelete) return
    const target = pendingDelete
    if (target.kind === 'product') setDeletingProductId(target.id)
    else setDeletingCategoryId(target.id)
    try {
      if (target.kind === 'product') await archiveProduct(client, target.id)
      else await archiveCategory(client, target.id)
      await onRefresh()
      setPendingDelete(undefined)
      setSuccessMessage(`${target.kind === 'product' ? 'Product' : 'Flavor'} successfully deleted.`)
    } catch (error) {
      onError(getErrorMessage(error))
    } finally {
      setDeletingProductId(undefined)
      setDeletingCategoryId(undefined)
    }
  }
  useEffect(() => {
    if (!productModalOpen) return
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') closeProductModal() }
    document.addEventListener('keydown', closeOnEscape)
    document.body.style.overflow = 'hidden'
    return () => { document.removeEventListener('keydown', closeOnEscape); document.body.style.overflow = '' }
  }, [productModalOpen, closeProductModal])
  useEffect(() => {
    if (!successMessage) return
    const timeout = window.setTimeout(() => setSuccessMessage(''), 4500)
    return () => window.clearTimeout(timeout)
  }, [successMessage])
  const ingredients = data.products.filter((product) => isStockable(product) && product.id !== editingId)
  const draftRecipes = recipeLines.map((line, index) => ({ ...line, id: String(index), stall_id: data.stall?.id ?? '', parent_product_id: editingId ?? '', quantity: Number(line.quantity) || 0, updated_at: '' }))
  const cogs = recipeCost(draftRecipes, data.products)
  const margin = Number(form.sale_price) - cogs
  return <>{pendingDelete && <ConfirmationDialog busy={Boolean(deletingProductId || deletingCategoryId)} confirmLabel={`Delete ${pendingDelete.kind}`} description={pendingDelete.kind === 'product' ? `${pendingDelete.name} will be removed from the product catalog. Historical sales and inventory records will be preserved.` : `${pendingDelete.name} will be removed from the available flavor list.`} onCancel={() => setPendingDelete(undefined)} onConfirm={() => void confirmCatalogDelete()} title={`Delete ${pendingDelete.name}?`} />}{successMessage && <div className="fixed right-4 top-4 z-[70] w-[min(24rem,calc(100vw-2rem))] shadow-lg"><Notice tone="success">{successMessage}</Notice></div>}<Panel title="Product catalog" description="Manage menu items, raw ingredients, packaging, recipes, and reorder thresholds." action={<div className="flex flex-col gap-2 sm:flex-row"><Input aria-label="Search products" placeholder="Search name or SKU" value={search} onChange={(event) => setSearch(event.target.value)} /><Button className="shrink-0" onClick={openAddProduct}>Add product</Button></div>}>
    <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Filter products by classification">{([['all', 'All'], ['sellable', 'Sellable Menu Items'], ['raw', 'Raw Ingredients'], ['packaging', 'Packaging']] as const).map(([value, label]) => <button key={value} type="button" aria-pressed={typeFilter === value} onClick={() => setTypeFilter(value)} className={`rounded-lg border px-3 py-2 text-sm font-semibold transition ${typeFilter === value ? 'border-violet-700 bg-violet-700 text-white' : 'border-[#eadcff] bg-white text-[#4b2a7a] hover:bg-[#f5ebff]'}`}>{label}</button>)}</div>
    {filtered.length === 0 ? <EmptyState title="No products found" description={search || typeFilter !== 'all' ? 'Try a different search or classification.' : 'Add your first product to begin building the catalog.'} /> : <Table><TableHead><th className="px-3 py-3">Product</th><th className="px-3 py-3">Classification</th><th className="px-3 py-3">Sale price / unit cost</th><th className="px-3 py-3">On hand</th><th className="px-3 py-3">Status</th><th className="px-3 py-3" /></TableHead><tbody>{filtered.map((product) => { const stockable = isStockable(product); const onHand = stock[product.id] ?? 0; const low = stockable && onHand <= product.low_stock_threshold; const productRecipes = data.recipes.filter((recipe) => recipe.parent_product_id === product.id); return <tr key={product.id} className="border-b border-slate-100"><TableCell><p className="font-medium">{product.name}</p><p className="text-xs text-slate-400">{product.sku} · {product.base_unit}</p>{product.product_type === 'sellable' && <p className="mt-0.5 text-xs font-medium text-[#5a1bb0]">POS category: {product.sell_category || data.categories.find((category) => category.id === product.category_id)?.name || 'Uncategorized'}</p>}</TableCell><TableCell><Badge tone={product.product_type}>{productTypeLabel(product.product_type)}</Badge></TableCell><TableCell>{stockable ? `Cost: ${unitMoney(unitCost(product))}/${product.base_unit}` : <><span>Sale: {money(product.sale_price)}</span>{productRecipes.length > 0 && <span className="block text-xs text-slate-500">COGS: {money(recipeCost(productRecipes, data.products))}</span>}</>}</TableCell><TableCell>{stockable ? `${onHand.toLocaleString()} ${product.base_unit}` : <span className="text-slate-500">— Made to order</span>}</TableCell><TableCell>{stockable ? <Badge tone={low ? 'warning' : 'success'}>{low ? 'Low stock' : 'Active'}</Badge> : <span className="text-slate-400">—</span>}</TableCell><TableCell><div className="flex gap-2"><Button variant="ghost" onClick={() => edit(product)}><EditIcon />Edit</Button><Button disabled={deletingProductId === product.id} variant="danger" onClick={() => void deleteProduct(product)}><DeleteIcon />{deletingProductId === product.id ? 'Deleting…' : 'Delete'}</Button></div></TableCell></tr> })}</tbody></Table>}
  </Panel>{productModalOpen && <div className="fixed inset-0 z-50 flex items-end bg-[#18002f]/55 p-0 backdrop-blur-[2px] sm:items-center sm:justify-center sm:p-6" onMouseDown={(event) => { if (event.target === event.currentTarget) closeProductModal() }}><section aria-labelledby="product-modal-title" aria-modal="true" className="product-modal-scrollbar max-h-[92vh] w-full overflow-y-auto rounded-t-3xl bg-[#fffdf8] shadow-2xl sm:max-w-3xl sm:rounded-3xl" role="dialog"><div className="sticky top-0 z-10 flex items-start justify-between gap-4 border-b border-[#eadcff] bg-[#fffdf8]/95 p-5 backdrop-blur"><div><p className="text-xs font-semibold uppercase tracking-[0.18em] text-[#5a1bb0]">Product catalog</p><h2 id="product-modal-title" className="mt-1 text-xl font-black text-[#220046]">{editingId ? 'Edit product' : 'Add product'}</h2><p className="mt-1 text-sm text-slate-500">Classify the item first; the form adapts to the inventory workflow.</p></div><button aria-label="Close product form" className="rounded-lg p-2 text-[#4b2a7a] transition hover:bg-[#f5ebff]" onClick={closeProductModal} type="button">×</button></div><form className="space-y-4 p-5" onSubmit={submit}><ProductFormFields categories={data.categories} sellCategories={sellCategories} form={form} setField={setField} />{form.product_type === 'sellable' && <fieldset className="rounded-2xl border border-[#eadcff] bg-white p-4"><div className="flex items-start justify-between gap-3"><div><legend className="font-bold text-[#220046]">Recipe</legend><p className="mt-1 text-xs text-slate-500">Ingredients are deducted automatically when this item is sold.</p></div><Button type="button" variant="secondary" onClick={() => setRecipeLines((current) => [...current, { ingredient_product_id: '', quantity: '1' }])}>Add ingredient</Button></div><div className="mt-4 space-y-3">{recipeLines.map((line, index) => <div className="grid gap-2 rounded-xl bg-[#fbf7ff] p-3 sm:grid-cols-[1fr_9rem_auto]" key={index}><Select aria-label={`Ingredient ${index + 1}`} value={line.ingredient_product_id} onChange={(event) => setRecipeLines((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, ingredient_product_id: event.target.value } : item))}><option value="">Choose ingredient</option>{ingredients.map((product) => <option key={product.id} value={product.id}>{product.name} ({product.base_unit})</option>)}</Select><Input aria-label={`Quantity ${index + 1}`} type="number" min="0.001" step="0.001" value={line.quantity} onChange={(event) => setRecipeLines((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, quantity: event.target.value } : item))} /><Button type="button" variant="ghost" onClick={() => setRecipeLines((current) => current.filter((_, itemIndex) => itemIndex !== index))}>Remove</Button></div>)}{recipeLines.length === 0 && <p className="text-sm text-slate-500">No ingredients added. Add at least one for automatic stock usage.</p>}</div><div className="mt-4 grid grid-cols-2 gap-3"><div className="rounded-xl bg-[#f4ecff] p-3"><p className="text-xs text-slate-500">Estimated COGS</p><p className="mt-1 font-black text-[#220046]">{money(cogs)}</p></div><div className={`rounded-xl p-3 ${margin >= 0 ? 'bg-emerald-50' : 'bg-red-50'}`}><p className="text-xs text-slate-500">Gross margin</p><p className={`mt-1 font-black ${margin >= 0 ? 'text-emerald-800' : 'text-red-800'}`}>{money(margin)} ({Number(form.sale_price) > 0 ? ((margin / Number(form.sale_price)) * 100).toFixed(1) : '0.0'}%)</p></div></div></fieldset>}{(form.product_type === 'raw' || form.product_type === 'packaging') && <p className="rounded-xl bg-emerald-50 px-3 py-2 text-sm text-emerald-800">Unit cost: <strong>{unitMoney(unitCost({ cost_price: Number(form.cost_price), pack_size: Number(form.pack_size), conversion_rate: Number(form.conversion_rate) }))}/{form.base_unit}</strong></p>}{message && <Notice>{message}</Notice>}<div className="flex flex-wrap justify-end gap-2 border-t border-slate-100 pt-4"><Button type="button" variant="ghost" onClick={closeProductModal}>Cancel</Button><Button disabled={saving}>{saving ? 'Saving…' : editingId ? 'Save changes' : 'Add product'}</Button></div></form><div className="border-t border-slate-100 p-5"><form onSubmit={addNewCategory}><p className="text-sm font-semibold text-[#39235f]">Manage flavors</p><div className="mt-2 flex flex-col gap-2 sm:flex-row"><Input aria-label="New flavor name" placeholder="e.g. Vanilla" value={categoryName} onChange={(event) => setCategoryName(event.target.value)} /><Button type="submit">Add flavor</Button></div></form>{data.categories.length > 0 && <ul aria-label="Existing flavors" className="mt-4 divide-y divide-slate-100 rounded-xl border border-slate-200">{data.categories.map((category) => <li className="flex items-center justify-between gap-3 px-3 py-2" key={category.id}><span className="text-sm font-medium text-[#39235f]">{category.name}</span><Button disabled={deletingCategoryId === category.id} type="button" variant="danger" onClick={() => void deleteFlavor(category.id, category.name)}>{deletingCategoryId === category.id ? 'Deleting…' : 'Delete'}</Button></li>)}</ul>}</div></section></div>}</>
}

function InventoryEntryForm({ mode, client, data, onRefresh, onError }: ScreenProps & { mode: 'receive' | 'adjust' }) {
  const [productId, setProductId] = useState('')
  const [quantity, setQuantity] = useState('')
  const [direction, setDirection] = useState<'set' | 'add' | 'remove'>('set')
  const [adjustmentUnit, setAdjustmentUnit] = useState<'packs' | 'base'>('packs')
  const [reason, setReason] = useState(mode === 'receive' ? 'Stock delivery' : '')
  const [message, setMessage] = useState('')
  const [messageTone, setMessageTone] = useState<'error' | 'success'>('error')
  const [saving, setSaving] = useState(false)
  const stock = getStockByProduct(data.inventory)
  const stockableProducts = data.products.filter(isStockable)
  const selectedProduct = stockableProducts.find((item) => item.id === productId)
  const receivedUnits = mode === 'receive' && selectedProduct && Number(quantity) > 0
    ? (() => { try { return receivedBaseUnits(Number(quantity), selectedProduct) } catch { return null } })()
    : null
  const adjustmentUnits = mode === 'adjust' && selectedProduct && quantity.trim()
    ? (() => { try { return targetBaseUnits(Number(quantity), adjustmentUnit, selectedProduct) } catch { return null } })()
    : null
  const displayedStock = selectedProduct ? stock[selectedProduct.id] ?? 0 : 0
  const previewDelta = adjustmentUnits === null ? null : direction === 'set'
    ? adjustmentUnits - displayedStock : direction === 'add' ? adjustmentUnits : -adjustmentUnits

  async function submit(event: FormEvent) {
    event.preventDefault()
    setMessage('')
    setMessageTone('error')
    const qty = Number(quantity)
    const product = stockableProducts.find((item) => item.id === productId)
    if (!product || !quantity.trim() || !Number.isFinite(qty) || qty < 0 ||
        (qty === 0 && (mode === 'receive' || direction !== 'set')) || !reason.trim()) {
      setMessage('Select a stockable product, enter a valid quantity, and provide a reason.')
      return
    }
    let delta: number
    try {
      const units = mode === 'receive' ? receivedBaseUnits(qty, product) : targetBaseUnits(qty, adjustmentUnit, product)
      if (mode === 'adjust' && direction === 'set') {
        setSaving(true)
        try {
          const result = await setStockOnHand(client, data.stall?.id ?? '', product.id, units, reason.trim())
          setQuantity('')
          await onRefresh()
          setMessageTone('success')
          setMessage(result.status === 'unchanged'
            ? `Stock was already ${result.target_stock.toLocaleString()} ${product.base_unit}.`
            : `Stock corrected from ${result.previous_stock.toLocaleString()} to ${result.target_stock.toLocaleString()} ${product.base_unit} (${result.quantity_delta > 0 ? '+' : ''}${result.quantity_delta.toLocaleString()} ${product.base_unit}).`)
        } catch (error) { onError(getErrorMessage(error)) }
        finally { setSaving(false) }
        return
      }
      delta = mode === 'receive' || direction === 'add' ? units : -units
    } catch (error) {
      setMessage(getErrorMessage(error))
      return
    }
    if (mode === 'adjust' && delta < 0 && (stock[product.id] ?? 0) + delta < 0) {
      setMessage('This adjustment would make stock negative.')
      return
    }
    setSaving(true)
    try {
      await addInventoryEntry(client, {
        stall_id: data.stall?.id ?? '', product_id: product.id, quantity_delta: delta,
        movement_type: mode === 'receive' ? 'receive' : 'adjustment', reason: reason.trim(),
      })
      setQuantity('')
      await onRefresh()
      setMessageTone('success')
      setMessage(mode === 'receive'
        ? `Stock received successfully: ${delta.toLocaleString()} ${product.base_unit} added.`
        : 'Inventory adjusted successfully.')
    } catch (error) { onError(getErrorMessage(error)) }
    finally { setSaving(false) }
  }

  return <Panel title={mode === 'receive' ? 'Receive stock' : 'Adjust inventory'} description={mode === 'receive' ? 'Enter the number of purchased packs; stock is stored in each product’s base unit.' : 'Set the counted total or add/remove stock in packs or base units.'}>
    {mode === 'receive' && <p className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-950">Missing a new product? Only items classified as <strong>Raw ingredient</strong> or <strong>Packaging</strong> appear here. Check its classification in Products.</p>}
    <form className="max-w-xl space-y-4" onSubmit={submit}>
      <Select label="Product" value={productId} onChange={(event) => setProductId(event.target.value)} required>
        <option value="">Choose a product</option>
        {stockableProducts.map((product) => <option key={product.id} value={product.id}>
          {product.name} · on hand {(stock[product.id] ?? 0).toLocaleString()} {product.base_unit}
        </option>)}
      </Select>
      {mode === 'adjust' && <><Select label="Adjustment action" value={direction} onChange={(event) => setDirection(event.target.value as 'set' | 'add' | 'remove')}><option value="set">Set on-hand total</option><option value="add">Add stock</option><option value="remove">Remove stock</option></Select><Select label="Enter quantity as" value={adjustmentUnit} onChange={(event) => setAdjustmentUnit(event.target.value as 'packs' | 'base')}><option value="packs">Packs</option><option value="base">{selectedProduct?.base_unit ?? 'Base units'}</option></Select></>}
      <Input label={mode === 'receive' ? 'Packs received' : `${direction === 'set' ? 'Counted on hand' : 'Quantity'} (${adjustmentUnit === 'packs' ? 'packs' : selectedProduct?.base_unit ?? 'base units'})`} type="number" min={mode === 'adjust' && direction === 'set' ? '0' : '0.001'} step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} required />
      {mode === 'receive' && selectedProduct && <p className="text-sm text-slate-600">
        1 pack = {(selectedProduct.pack_size * selectedProduct.conversion_rate).toLocaleString()} {selectedProduct.base_unit}
        {receivedUnits !== null && ` · This delivery adds ${receivedUnits.toLocaleString()} ${selectedProduct.base_unit}`}
      </p>}
      {mode === 'adjust' && selectedProduct && <p className="text-sm text-slate-600">
        Currently displayed: {displayedStock.toLocaleString()} {selectedProduct.base_unit} · 1 pack = {(selectedProduct.pack_size * selectedProduct.conversion_rate).toLocaleString()} {selectedProduct.base_unit}
        {adjustmentUnits !== null && ` · ${direction === 'set' ? 'Target' : 'Change'}: ${adjustmentUnits.toLocaleString()} ${selectedProduct.base_unit}`}
        {previewDelta !== null && ` · Estimated ledger adjustment: ${previewDelta > 0 ? '+' : ''}${previewDelta.toLocaleString()} ${selectedProduct.base_unit}`}
      </p>}
      <Textarea label="Reason" rows={3} placeholder="Supplier delivery, count correction, damaged stock…" value={reason} onChange={(event) => setReason(event.target.value)} required />
      {message && <Notice tone={messageTone}>{message}</Notice>}
      <Button disabled={saving}>{saving ? 'Saving…' : mode === 'receive' ? 'Record delivery' : 'Save adjustment'}</Button>
    </form>
  </Panel>
}

export function ReceivingScreen(props: ScreenProps) { return <InventoryEntryForm {...props} mode="receive" /> }
export function AdjustmentsScreen(props: ScreenProps) { return <InventoryEntryForm {...props} mode="adjust" /> }

export function PricingScreen({ client, data, onRefresh, onError }: ScreenProps) {
  const [drafts, setDrafts] = useState<Record<string, { sale_price: string; cost_price: string; pack_size: string; conversion_rate: string }>>({})
  const [savingId, setSavingId] = useState<string>()
  function draft(product: Product) { return drafts[product.id] ?? { sale_price: String(product.sale_price), cost_price: String(product.cost_price), pack_size: String(product.pack_size), conversion_rate: String(product.conversion_rate) } }
  async function save(product: Product) { const values = draft(product); const numbers = [values.sale_price, values.cost_price, values.pack_size, values.conversion_rate].map(Number); if (numbers.some((value) => !Number.isFinite(value) || value < 0) || numbers[2] <= 0 || numbers[3] <= 0) { onError('Enter valid prices and positive pack/conversion values.'); return } setSavingId(product.id); try { await saveProduct(client, { stall_id: product.stall_id, category_id: product.category_id, sku: product.sku, name: product.name, unit: product.unit, sale_price: numbers[0], cost_price: numbers[1], low_stock_threshold: product.low_stock_threshold, pack_size: numbers[2], conversion_rate: numbers[3], is_sellable: product.is_sellable, product_type: product.product_type, base_unit: product.base_unit }, product.id); await onRefresh() } catch (error) { onError(getErrorMessage(error)) } finally { setSavingId(undefined) } }
  return <Panel title="Price and conversion management" description="Update retail prices, raw costs, and pack-to-usable-unit conversions without an app release.">{data.products.length === 0 ? <EmptyState title="No products yet" description="Add products before changing their prices." /> : <Table><TableHead><th className="px-3 py-3">Product</th><th className="px-3 py-3">Sale price</th><th className="px-3 py-3">Cost price</th><th className="px-3 py-3">Pack size</th><th className="px-3 py-3">Usable units / pack</th><th className="px-3 py-3" /></TableHead><tbody>{data.products.map((product) => { const values = draft(product); const set = (field: keyof typeof values, value: string) => setDrafts((current) => ({ ...current, [product.id]: { ...values, [field]: value } })); return <tr key={product.id} className="border-b border-slate-100"><TableCell><p className="font-medium">{product.name}</p><p className="text-xs text-slate-400">{product.unit}</p></TableCell><TableCell><input className="w-28 rounded border border-slate-300 px-2 py-1" type="number" min="0" step="0.01" value={values.sale_price} onChange={(event) => set('sale_price', event.target.value)} /></TableCell><TableCell><input className="w-28 rounded border border-slate-300 px-2 py-1" type="number" min="0" step="0.01" value={values.cost_price} onChange={(event) => set('cost_price', event.target.value)} /></TableCell><TableCell><input className="w-24 rounded border border-slate-300 px-2 py-1" type="number" min="0.001" step="0.001" value={values.pack_size} onChange={(event) => set('pack_size', event.target.value)} /></TableCell><TableCell><input className="w-28 rounded border border-slate-300 px-2 py-1" type="number" min="0.001" step="0.001" value={values.conversion_rate} onChange={(event) => set('conversion_rate', event.target.value)} /></TableCell><TableCell><Button disabled={savingId === product.id} onClick={() => void save(product)}>{savingId === product.id ? 'Saving…' : 'Save'}</Button></TableCell></tr> })}</tbody></Table>}</Panel>
}

export function TransactionsScreen({ client, data, onRefresh, onError, amountsVisible }: ScreenProps) {
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const [status, setStatus] = useState('all')
  const [search, setSearch] = useState('')
  const [selectedId, setSelectedId] = useState<string>()
  const [reason, setReason] = useState('Customer changed mind')
  const [restock, setRestock] = useState(true)
  const [receiptId, setReceiptId] = useState<string>()
  const [receiptItems, setReceiptItems] = useState<TransactionItem[]>([])
  const [receiptLoading, setReceiptLoading] = useState(false)
  const [receiptError, setReceiptError] = useState('')
  const receiptTransaction = data.transactions.find((transaction) => transaction.id === receiptId)
  async function viewReceipt(transactionId: string) {
    setReceiptId(transactionId)
    setReceiptItems([])
    setReceiptError('')
    setReceiptLoading(true)
    try { setReceiptItems(await getTransactionReceiptItems(client, transactionId)) }
    catch (error) { setReceiptError(getErrorMessage(error)) }
    finally { setReceiptLoading(false) }
  }
  const filtered = data.transactions.filter((transaction) => (status === 'all' || transaction.status === status) && transaction.receipt_number.toLowerCase().includes(search.toLowerCase()))
  async function reverse() { if (!selectedId || !reason.trim()) return; try { await reverseTransaction(client, selectedId, reason.trim(), restock); setSelectedId(undefined); await onRefresh() } catch (error) { onError(getErrorMessage(error)) } }
  return <><Panel title="Transaction history" description="Review sales receipts and reverse an eligible transaction with an audit reason." action={<div className="flex gap-2"><Input aria-label="Search receipts" placeholder="Receipt number" value={search} onChange={(event) => setSearch(event.target.value)} /><Select aria-label="Filter status" value={status} onChange={(event) => setStatus(event.target.value)}><option value="all">All statuses</option><option value="completed">Completed</option><option value="voided">Voided</option><option value="refunded">Refunded</option></Select></div>}>{filtered.length === 0 ? <EmptyState title="No transactions found" description="Sales will appear here after the POS syncs them." /> : <Table><TableHead><th className="px-3 py-3">Receipt</th><th className="px-3 py-3">Date</th><th className="px-3 py-3">Total</th><th className="px-3 py-3">Status</th><th className="px-3 py-3">Actions</th></TableHead><tbody>{filtered.map((transaction) => <tr key={transaction.id} className="border-b border-slate-100"><TableCell className="font-medium">{transaction.receipt_number}</TableCell><TableCell>{dateTime.format(new Date(transaction.occurred_at))}</TableCell><TableCell>{money(transaction.total_amount)}</TableCell><TableCell><Badge tone={transaction.status === 'completed' ? 'success' : transaction.status === 'voided' ? 'danger' : 'warning'}>{transaction.status}</Badge></TableCell><TableCell><div className="flex flex-wrap gap-2"><Button variant="secondary" onClick={() => void viewReceipt(transaction.id)}>View receipt</Button>{transaction.status === 'completed' && <Button variant="danger" onClick={() => setSelectedId(transaction.id)}>Void / reverse</Button>}</div></TableCell></tr>)}</tbody></Table>}{selectedId && <div className="mt-5 rounded-xl border border-red-200 bg-red-50 p-4"><p className="font-semibold text-red-800">Reverse transaction</p><p className="mt-1 text-sm text-red-700">Choose whether the sold stock should return to inventory.</p><div className="mt-3 grid gap-3 sm:grid-cols-2"><Select label="Reason" value={reason} onChange={(event) => setReason(event.target.value)}><option>Customer changed mind</option><option>Incorrect order</option><option>Quality issue</option></Select><Select label="Stock action" value={restock ? 'restock' : 'waste'} onChange={(event) => setRestock(event.target.value === 'restock')}><option value="restock">Restock items</option><option value="waste">Waste stock</option></Select></div><div className="mt-3 flex gap-2"><Button variant="danger" onClick={() => void reverse()}>Confirm reversal</Button><Button variant="ghost" onClick={() => setSelectedId(undefined)}>Cancel</Button></div></div>}</Panel>{receiptTransaction && <div className="fixed inset-0 z-50 flex items-center justify-center bg-[#18002f]/55 p-4" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setReceiptId(undefined) }}><section aria-label={`Receipt ${receiptTransaction.receipt_number}`} aria-modal="true" className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-2xl bg-white p-6 shadow-2xl" role="dialog"><div className="flex items-start justify-between gap-3"><div><p className="text-xs font-semibold uppercase tracking-widest text-[#5a1bb0]">{data.stall?.name ?? 'Coolerz'}</p><h2 className="mt-1 text-xl font-black text-[#220046]">Receipt {receiptTransaction.receipt_number}</h2><p className="mt-1 text-sm text-slate-500">{dateTime.format(new Date(receiptTransaction.occurred_at))}</p></div><Button variant="ghost" onClick={() => setReceiptId(undefined)}>Close</Button></div><div className="mt-4"><Badge tone={receiptTransaction.status === 'completed' ? 'success' : receiptTransaction.status === 'voided' ? 'danger' : 'warning'}>{receiptTransaction.status}</Badge></div><div className="mt-5 border-y border-slate-200 py-3">{receiptLoading ? <p className="text-sm text-slate-500">Loading items…</p> : receiptError ? <Notice tone="error">{receiptError}</Notice> : receiptItems.length === 0 ? <p className="text-sm text-slate-500">No line items found for this transaction.</p> : receiptItems.map((item) => <div className="flex justify-between gap-3 py-1 text-sm" key={item.id}><div><p className="font-medium">{item.product_name}</p><p className="text-slate-500">{item.quantity} × {money(item.unit_price)}</p></div><p>{money(item.line_total)}</p></div>)}</div><div className="mt-3 space-y-2 text-sm"><div className="flex justify-between"><span>Subtotal</span><span>{money(receiptTransaction.subtotal)}</span></div><div className="flex justify-between font-bold"><span>Total</span><span>{money(receiptTransaction.total_amount)}</span></div>{receiptTransaction.cash_received !== null && <div className="flex justify-between"><span>Cash received</span><span>{money(receiptTransaction.cash_received)}</span></div>}{receiptTransaction.change_amount !== null && <div className="flex justify-between"><span>Change</span><span>{money(receiptTransaction.change_amount)}</span></div>}</div></section></div>}</>
}

export function ProductPerformanceScreen({ data, amountsVisible }: ScreenProps) {
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const today = getBusinessDateKey()
  const [from, setFrom] = useState(shiftDateKey(today, -29))
  const [to, setTo] = useState(today)
  const products = useMemo(
    () => getProductPerformance(data.transactions, data.transactionItems, from, to),
    [data.transactionItems, data.transactions, from, to],
  )
  const unitsSold = products.reduce((sum, product) => sum + product.unitsSold, 0)
  const productRevenue = products.reduce((sum, product) => sum + product.revenue, 0)
  const topShare = productRevenue > 0 ? ((products[0]?.revenue ?? 0) / productRevenue) * 100 : 0

  return (
    <div className="space-y-5">
      <Panel title="Product performance" description="See which products drive unit sales and revenue. This report is read-only." action={<div className="grid w-full grid-cols-2 gap-2 sm:w-auto"><Input label="From" type="date" value={from} onChange={(event) => setFrom(event.target.value)} /><Input label="To" type="date" value={to} onChange={(event) => setTo(event.target.value)} /></div>}>
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          {[['Product revenue', money(productRevenue)], ['Units sold', unitsSold.toLocaleString()], ['Products sold', products.length.toLocaleString()], ['Top product share', `${topShare.toFixed(0)}%`]].map(([label, value]) => <div className="rounded-2xl bg-[#f7f1ff] p-4" key={label}><p className="text-xs font-semibold text-[#6b4d89]">{label}</p><p className="mt-2 text-xl font-black text-[#220046]">{value}</p></div>)}
        </div>
      </Panel>

      <Panel title="Top products by revenue" description={`${from} to ${to}`}>
        <HorizontalBarChart items={products.slice(0, 8).map((product) => ({ label: product.name, value: product.revenue, detail: `${product.unitsSold.toLocaleString()} units · ${product.orders} orders` }))} formatValue={(value) => money(value)} />
      </Panel>

      <Panel title="Product detail" description="Completed POS sales in the selected period.">
        {products.length === 0 ? <EmptyState title="No product sales" description="Try a wider date range or wait for completed sales to sync." /> : <>
          <div className="space-y-3 md:hidden">{products.map((product, index) => <article className="rounded-2xl border border-[#eadcff] bg-[#fffdf8] p-4" key={product.name}><div className="flex items-start justify-between gap-3"><div><p className="text-xs font-bold text-[#8b6ca8]">#{index + 1}</p><h3 className="mt-1 font-bold text-[#220046]">{product.name}</h3></div><p className="font-black text-[#5a1bb0]">{money(product.revenue)}</p></div><div className="mt-3 grid grid-cols-2 gap-2 text-xs text-slate-500"><span>{product.unitsSold.toLocaleString()} units sold</span><span className="text-right">{product.orders} orders</span></div></article>)}</div>
          <div className="hidden md:block"><Table><TableHead><th className="px-3 py-3">Rank</th><th className="px-3 py-3">Product</th><th className="px-3 py-3 text-right">Units sold</th><th className="px-3 py-3 text-right">Orders</th><th className="px-3 py-3 text-right">Revenue</th></TableHead><tbody>{products.map((product, index) => <tr className="border-b border-slate-100" key={product.name}><TableCell>#{index + 1}</TableCell><TableCell className="font-semibold">{product.name}</TableCell><TableCell className="text-right">{product.unitsSold.toLocaleString()}</TableCell><TableCell className="text-right">{product.orders}</TableCell><TableCell className="text-right font-bold text-[#5a1bb0]">{money(product.revenue)}</TableCell></tr>)}</tbody></Table></div>
        </>}
      </Panel>
    </div>
  )
}

export function ReportsScreen({ data, amountsVisible }: ScreenProps) {
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const today = getBusinessDateKey()
  const [from, setFrom] = useState(shiftDateKey(today, -6))
  const [to, setTo] = useState(today)
  const overheadItems = useMemo(
    () => (data.stall?.overhead_config?.length ? data.stall.overhead_config : DEFAULT_OVERHEAD_ITEMS),
    [data.stall?.overhead_config],
  )
  const report = useMemo(() => {
    const transactions = data.transactions.filter((transaction) => {
      const date = getBusinessDateKey(transaction.occurred_at)
      return date >= from && date <= to
    })
    return {
      transactions,
      completed: transactions.filter((transaction) => transaction.status === 'completed'),
      voided: transactions.filter((transaction) => transaction.status === 'voided'),
    }
  }, [data.transactions, from, to])
  const profitDays = useMemo(
    () => getDailyProfitReport(
      data.products,
      data.inventory,
      data.transactions,
      data.transactionItems,
      from,
      to,
      overheadItems,
      data.businessDays.map((day) => day.business_date),
      data.stall?.financial_report_reset_at,
      data.revenueDeductions,
      data.dailyClosings,
    ),
    [data, from, to, overheadItems],
  )
  const revenueTrend = useMemo(
    () => getRevenueTrend(data.transactions, from, to),
    [data.transactions, from, to],
  )
  const totals = useMemo(() => profitDays.reduce(
    (acc, day) => ({
      revenue: acc.revenue + day.revenue,
      cogs: acc.cogs + day.cogs,
      wasteCost: acc.wasteCost + day.wasteCost,
      overhead: acc.overhead + day.fixedOverhead,
      deductions: acc.deductions + day.revenueDeduction,
      profitDeductions: acc.profitDeductions + day.profitDeduction,
      netProfit: acc.netProfit + day.netProfit,
    }),
    { revenue: 0, cogs: 0, wasteCost: 0, overhead: 0, deductions: 0, profitDeductions: 0, netProfit: 0 },
  ), [profitDays])
  const averageSale = report.completed.length > 0 ? totals.revenue / report.completed.length : 0
  const reportedDays = profitDays.length

  function exportReport() {
    downloadCsv(`sales-report-${from}-to-${to}.csv`, report.transactions.map((transaction) => ({
      receipt: transaction.receipt_number,
      occurred_at: transaction.occurred_at,
      status: transaction.status,
      subtotal: transaction.subtotal,
      total: transaction.total_amount,
      cash_received: transaction.cash_received,
      change: transaction.change_amount,
    })))
  }

  return (
    <div className="space-y-5">
      {!data.deductionsAvailable && <Notice tone="info">POS deductions are unavailable until the revenue-deductions database migration is applied. Profit may be incomplete.</Notice>}
      <Panel title="Sales analytics" description="Revenue and profit monitoring for completed POS sales." action={<div className="grid w-full grid-cols-2 gap-2 sm:w-auto sm:grid-cols-[minmax(0,9rem)_minmax(0,9rem)_auto] sm:items-end"><Input label="From" type="date" value={from} onChange={(event) => setFrom(event.target.value)} /><Input label="To" type="date" value={to} onChange={(event) => setTo(event.target.value)} /><Button className="col-span-2 w-full sm:col-span-1 sm:w-auto" onClick={exportReport} disabled={report.transactions.length === 0}>Download CSV</Button></div>}>
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <div className="rounded-2xl bg-[#f4ecff] p-4"><p className="text-xs font-semibold text-[#5a1bb0]">Revenue</p><p className="mt-2 text-xl font-black text-[#220046] sm:text-2xl">{money(totals.revenue)}</p><p className="mt-1 text-xs text-slate-500">{report.completed.length} completed sales</p></div>
          <div className={`rounded-2xl p-4 ${totals.netProfit >= 0 ? 'bg-emerald-50' : 'bg-red-50'}`}><p className={`text-xs font-semibold ${totals.netProfit >= 0 ? 'text-emerald-700' : 'text-red-700'}`}>Net profit</p><p className={`mt-2 text-xl font-black sm:text-2xl ${totals.netProfit >= 0 ? 'text-emerald-900' : 'text-red-900'}`}>{money(totals.netProfit)}</p><p className="mt-1 text-xs text-slate-500">Revenue − COGS − waste − overhead − additional POS expenses</p></div>
          <div className="rounded-2xl bg-[#fff7e8] p-4"><p className="text-xs font-semibold text-amber-700">Average sale</p><p className="mt-2 text-xl font-black text-amber-950 sm:text-2xl">{money(averageSale)}</p><p className="mt-1 text-xs text-slate-500">Per completed order</p></div>
          <div className="rounded-2xl bg-slate-100 p-4"><p className="text-xs font-semibold text-slate-600">Voided sales</p><p className="mt-2 text-xl font-black text-slate-900 sm:text-2xl">{report.voided.length}</p><p className="mt-1 text-xs text-slate-500">In selected period</p></div>
        </div>
      </Panel>

      {report.completed.length === 0 && (totals.overhead > 0 || totals.wasteCost > 0) && <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-amber-950" role="status">
        <div className="flex items-start gap-3">
          <svg aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0 text-amber-700" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9" /><path d="M12 11v5m0-8h.01" /></svg>
          <div><p className="font-bold">Why is net profit negative when revenue is ₱0?</p><p className="mt-1 text-sm leading-6">This period still contains <strong>{reportedDays} reported day{reportedDays === 1 ? '' : 's'}</strong>, with <strong>{money(totals.overhead)} fixed overhead</strong>{totals.wasteCost > 0 ? <> and <strong>{money(totals.wasteCost)} recorded waste</strong></> : null}. Use <strong>Reset sales &amp; operating history</strong> to clear completed operating days together with their sales reports.</p></div>
        </div>
      </div>}

      <Panel title={`Revenue and orders by day · ${formatDateRangeLabel(from, to)}`} description="Completed sales in the selected period.">
        <RevenueTrendChart points={revenueTrend.map((day) => ({ date: day.date, value: day.revenue, orders: day.orders }))} formatValue={(value) => money(value)} showRevenue={amountsVisible} />
      </Panel>

      <div className="grid gap-5 lg:grid-cols-[0.8fr_1.2fr]">
        <Panel title="Cost summary" description="Amounts used in the profit estimate.">
          <HorizontalBarChart items={[
            { label: 'Cost of goods sold', value: totals.cogs },
            { label: 'Fixed overhead', value: totals.overhead },
            { label: 'Waste', value: totals.wasteCost },
            { label: 'Additional POS expenses', value: totals.profitDeductions },
          ]} formatValue={(value) => money(value)} />
        </Panel>
        <Panel title="Recent activity" description="Latest transactions in this date range.">
          {report.transactions.length === 0 ? <EmptyState title="No transactions" description="Choose a different date range or wait for POS synchronization." /> : <div className="divide-y divide-slate-100">{report.transactions.slice(0, 8).map((transaction) => <div className="flex items-center justify-between gap-3 py-3 first:pt-0 last:pb-0" key={transaction.id}><div className="min-w-0"><p className="truncate text-sm font-semibold text-[#39235f]">{transaction.receipt_number}</p><p className="mt-0.5 text-xs text-slate-400">{dateTime.format(new Date(transaction.occurred_at))}</p></div><div className="text-right"><p className="text-sm font-bold">{money(transaction.total_amount)}</p><Badge tone={transaction.status === 'completed' ? 'success' : 'warning'}>{transaction.status}</Badge></div></div>)}</div>}
        </Panel>
      </div>

      {profitDays.length > 0 && <Panel title="Daily breakdown" description="Revenue, costs, and net profit for each active day.">
        <div className="space-y-3 md:hidden">{profitDays.map((day) => <article className="rounded-2xl border border-[#eadcff] bg-[#fffdf8] p-4" key={day.businessDate}><div className="flex items-start justify-between gap-3"><div><p className="text-xs font-semibold text-slate-500">{day.businessDate}</p><p className="mt-1 text-lg font-black text-[#220046]">{money(day.revenue)}</p><p className="text-xs text-slate-400">{day.completedSales} sales</p></div><p className={`font-black ${day.netProfit >= 0 ? 'text-emerald-700' : 'text-red-700'}`}>{money(day.netProfit)}</p></div><div className="mt-3 grid grid-cols-2 gap-2 border-t border-slate-100 pt-3 text-xs text-slate-500"><span>COGS<br/><strong>{money(day.cogs)}</strong></span><span>Waste<br/><strong>{money(day.wasteCost)}</strong></span><span>Overhead<br/><strong>{money(day.fixedOverhead)}</strong></span><span>POS cash taken<br/><strong>{money(day.revenueDeduction)}</strong></span><span>Additional POS expense<br/><strong>{money(day.profitDeduction)}</strong></span></div></article>)}</div>
        <div className="hidden md:block"><Table><TableHead><th className="px-3 py-3">Date</th><th className="px-3 py-3 text-right">Revenue</th><th className="px-3 py-3 text-right">COGS</th><th className="px-3 py-3 text-right">Waste</th><th className="px-3 py-3 text-right">Overhead</th><th className="px-3 py-3 text-right">POS cash taken</th><th className="px-3 py-3 text-right">Additional POS expense</th><th className="px-3 py-3 text-right">Net profit</th><th className="px-3 py-3 text-right">Sales</th></TableHead><tbody>{profitDays.map((day) => <tr className="border-b border-slate-100" key={day.businessDate}><TableCell className="font-medium">{day.businessDate}</TableCell><TableCell className="text-right">{money(day.revenue)}</TableCell><TableCell className="text-right">{money(day.cogs)}</TableCell><TableCell className="text-right">{money(day.wasteCost)}</TableCell><TableCell className="text-right">{money(day.fixedOverhead)}</TableCell><TableCell className="text-right">{money(day.revenueDeduction)}</TableCell><TableCell className="text-right">{money(day.profitDeduction)}</TableCell><TableCell className={`text-right font-semibold ${day.netProfit >= 0 ? 'text-emerald-700' : 'text-red-700'}`}>{money(day.netProfit)}</TableCell><TableCell className="text-right">{day.completedSales}</TableCell></tr>)}</tbody></Table></div>
      </Panel>}
    </div>
  )
}

export function DailyCloseScreen({ data, amountsVisible }: ScreenProps) {
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const [date, setDate] = useState(getBusinessDateKey())
  const [physical, setPhysical] = useState<Record<string, string>>({})
  const overheadItems = data.stall?.overhead_config?.length ? data.stall.overhead_config : DEFAULT_OVERHEAD_ITEMS
  const [report] = getDailyProfitReport(data.products, data.inventory, data.transactions, data.transactionItems, date, date, overheadItems, data.businessDays.map((day) => day.business_date), data.stall?.financial_report_reset_at, data.revenueDeductions, data.dailyClosings)
  const emptyReport = { businessDate: date, revenue: 0, cogs: 0, wasteCost: 0, fixedOverhead: 0, revenueDeduction: 0, profitDeduction: 0, netProfit: 0, completedSales: 0, voidedSales: 0 }
  const day = report ?? emptyReport
  const reconciliation = getDailyReconciliation(date, data.products, data.recipes, data.inventory, data.transactions, data.transactionItems)
  const saved = data.dailyClosings.find((closing) => closing.business_date === date)
  const dayDeductions = data.revenueDeductions.filter((entry) => entry.business_date === date)
  return <div className="space-y-5">
    {!data.deductionsAvailable && <Notice tone="info">POS deductions are unavailable until the revenue-deductions database migration is applied. Profit may be incomplete.</Notice>}
    <Panel title="Daily Close & Profit" description="Review the day’s cash, recipe usage, costs, and physical stock before final reconciliation." action={<Input label="Business date" type="date" value={date} onChange={(event) => { setDate(event.target.value); setPhysical({}) }} />}>
      {saved && <Notice tone="success">Closed at {dateTime.format(new Date(saved.closed_at))} · collected {money(saved.collected_cash)} vs expected {money(saved.expected_cash)}.</Notice>}
      <div className="mt-4 grid grid-cols-2 gap-3 lg:grid-cols-3">
        {[['Gross sales', day.revenue], ['COGS', day.cogs], ['Waste', day.wasteCost], ['Fixed overhead', day.fixedOverhead], ['POS cash taken', day.revenueDeduction], ['Additional POS expense', day.profitDeduction]].map(([label, value]) => <div className="rounded-2xl bg-[#f7f1ff] p-4" key={label}><p className="text-xs font-semibold text-[#6b4d89]">{label}</p><p className="mt-2 text-xl font-black text-[#220046]">{money(value as number)}</p></div>)}
        <div className={`col-span-2 rounded-2xl p-4 lg:col-span-1 ${day.netProfit >= 0 ? 'bg-emerald-100' : 'bg-red-100'}`}><p className={`text-xs font-semibold ${day.netProfit >= 0 ? 'text-emerald-700' : 'text-red-700'}`}>Net profit</p><p className={`mt-2 text-xl font-black ${day.netProfit >= 0 ? 'text-emerald-950' : 'text-red-950'}`}>{money(day.netProfit)}</p></div>
      </div>
    </Panel>
    <Panel title="POS cash deductions" description="Cashier-recorded amounts taken from sales cash. These are separate from the fixed operating expenses above.">
      {dayDeductions.length === 0 ? <p className="text-sm text-slate-500">No itemized POS deductions for this day.</p> : <div className="divide-y divide-slate-100">{dayDeductions.map((entry) => <div className="flex justify-between gap-4 py-3" key={entry.id}><div><p className="font-semibold text-[#39235f]">{entry.reason}</p><p className="text-xs text-slate-500">{dateTime.format(new Date(entry.occurred_at))} · {entry.affects_profit ? 'Additional profit expense' : 'Cash only; already included in overhead or transfer'}</p></div><p className="font-bold">{money(entry.amount)}</p></div>)}</div>}
      {dayDeductions.length === 0 && day.revenueDeduction > 0 && <p className="mt-2 text-xs text-amber-700">The total comes from a legacy daily closing without itemized records.</p>}
    </Panel>
    <div className="grid gap-5 lg:grid-cols-[0.65fr_1.35fr]">
      <Panel title="Overhead breakdown" description="Itemized daily operating cost."><div className="divide-y divide-slate-100">{overheadItems.map((item) => <div className="flex items-center justify-between gap-3 py-3" key={item.key}><div><p className="text-sm font-semibold text-[#39235f]">{item.label}</p>{item.description && <p className="text-xs text-slate-400">{item.description}</p>}</div><p className="font-bold text-[#220046]">{money(item.dailyRate)}</p></div>)}</div></Panel>
      <Panel title="Inventory reconciliation" description="Expected stock uses starting quantity, receipts, recipe-based sales usage, and recorded waste.">
        {reconciliation.length === 0 ? <EmptyState title="No inventory components" description="Add raw ingredients or packaging products to begin reconciliation." /> : <Table><TableHead><th className="px-3 py-3">Item</th><th className="px-3 py-3 text-right">Starting</th><th className="px-3 py-3 text-right">Sales usage</th><th className="px-3 py-3 text-right">Waste</th><th className="px-3 py-3 text-right">Expected</th><th className="px-3 py-3">Physical count</th><th className="px-3 py-3 text-right">Variance</th></TableHead><tbody>{reconciliation.map((row) => { const count = physical[row.product.id] === undefined || physical[row.product.id] === '' ? null : Number(physical[row.product.id]); const variance = count === null || !Number.isFinite(count) ? null : count - row.expected; return <tr className="border-b border-slate-100" key={row.product.id}><TableCell><p className="font-semibold">{row.product.name}</p><p className="text-xs text-slate-400">{row.product.base_unit}</p></TableCell><TableCell className="text-right">{row.starting.toLocaleString()}</TableCell><TableCell className="text-right">{row.salesUsage.toLocaleString()}</TableCell><TableCell className="text-right">{row.waste.toLocaleString()}</TableCell><TableCell className="text-right font-bold">{row.expected.toLocaleString()}</TableCell><TableCell><input aria-label={`Physical count for ${row.product.name}`} className="w-28 rounded-lg border border-[#dfd4f3] px-2 py-2" min="0" step="0.001" type="number" value={physical[row.product.id] ?? ''} onChange={(event) => setPhysical((current) => ({ ...current, [row.product.id]: event.target.value }))} /></TableCell><TableCell className={`text-right font-semibold ${variance === null ? 'text-slate-400' : Math.abs(variance) < 0.001 ? 'text-emerald-700' : 'text-red-700'}`}>{variance === null ? '—' : variance.toLocaleString()}</TableCell></tr> })}</tbody></Table>}
      </Panel>
    </div>
  </div>
}

export function OperatingDaysScreen({ client, data, amountsVisible, onRefresh, onError, canManageOperatingDays }: ScreenProps) {
  const money = (value: number) => formatFinancialAmount(value, amountsVisible)
  const openDay = data.businessDays.find((day) => day.closed_at === null)
  const [closeTarget, setCloseTarget] = useState<WorkspaceData['businessDays'][number] | null>(null)
  const [collectedCash, setCollectedCash] = useState('')
  const [closeReason, setCloseReason] = useState('')
  const [closing, setClosing] = useState(false)
  const [message, setMessage] = useState('')

  async function confirmAdminClose() {
    if (!closeTarget) return
    setClosing(true)
    try {
      await adminCloseOpenBusinessDay(client, closeTarget.id, Number(collectedCash), closeReason.trim())
      setCloseTarget(null)
      setCollectedCash('')
      setCloseReason('')
      setMessage('Operating day closed from IMS. The old POS was disabled and the closing was added to the audit log.')
      await onRefresh()
    } catch (error) {
      onError(getErrorMessage(error))
    } finally {
      setClosing(false)
    }
  }

  const closeAction = (day: WorkspaceData['businessDays'][number]) => canManageOperatingDays && !day.closed_at
    ? <Button variant="danger" onClick={() => { setCloseTarget(day); setCollectedCash(''); setCloseReason(''); setMessage('') }}>Close from IMS</Button>
    : null

  return <div className="space-y-6">
    {message && <Notice tone="success">{message}</Notice>}
    {openDay && <Notice tone="info">This stall has an open operating day from {dateTime.format(new Date(openDay.opened_at))}. If its POS is available, close and sync from the POS. Use “Close from IMS” only when the phone is unavailable or its local app data was lost.</Notice>}
    <Panel title="Opening and closing history" description="Times are recorded by the Cashier POS and shown in your local time.">
      {data.businessDays.length === 0 ? <EmptyState title="No operating days yet" description="The first day will appear after a Cashier opens the Android POS and it syncs." /> : <>
        <div className="space-y-3 md:hidden">{data.businessDays.map((day) => <article className="rounded-2xl border border-[#eadcff] bg-[#fffdf8] p-4" key={day.id}><div className="flex items-start justify-between gap-3"><div><p className="text-xs font-semibold text-slate-500">Business date</p><p className="mt-1 font-black text-[#220046]">{day.business_date}</p></div>{day.closed_at ? <Badge tone="neutral">Closed</Badge> : <Badge tone="success">Open</Badge>}</div><dl className="mt-4 grid grid-cols-2 gap-3 text-xs"><div><dt className="text-slate-400">Opened</dt><dd className="mt-1 font-semibold text-slate-700">{dateTime.format(new Date(day.opened_at))}</dd></div><div><dt className="text-slate-400">Closed</dt><dd className="mt-1 font-semibold text-slate-700">{day.closed_at ? dateTime.format(new Date(day.closed_at)) : 'Still open'}</dd></div><div><dt className="text-slate-400">Closing cash</dt><dd className="mt-1 font-semibold text-slate-700">{day.closing_cash_total === null ? '—' : money(day.closing_cash_total)}</dd></div><div><dt className="text-slate-400">Notes</dt><dd className="mt-1 font-semibold text-slate-700">{[day.opening_notes, day.closing_notes].filter(Boolean).join(' · ') || '—'}</dd></div></dl>{closeAction(day) && <div className="mt-4 border-t border-[#f1e8ff] pt-3">{closeAction(day)}</div>}</article>)}</div>
        <div className="hidden md:block"><Table>
        <TableHead><th className="px-3 py-3">Business date</th><th className="px-3 py-3">Opened</th><th className="px-3 py-3">Closed</th><th className="px-3 py-3 text-right">Closing cash</th><th className="px-3 py-3">Notes</th><th className="px-3 py-3">Action</th></TableHead>
        <tbody>{data.businessDays.map((day) => <tr className="border-b border-slate-100" key={day.id}>
          <TableCell className="font-medium">{day.business_date}</TableCell>
          <TableCell>{dateTime.format(new Date(day.opened_at))}</TableCell>
          <TableCell>{day.closed_at ? dateTime.format(new Date(day.closed_at)) : <Badge tone="success">Open</Badge>}</TableCell>
          <TableCell className="text-right">{day.closing_cash_total === null ? '—' : money(day.closing_cash_total)}</TableCell>
          <TableCell className="max-w-xs text-xs text-slate-500">{[day.opening_notes, day.closing_notes].filter(Boolean).join(' · ') || '—'}</TableCell>
          <TableCell>{closeAction(day)}</TableCell>
        </tr>)}</tbody>
        </Table></div>
      </>}
    </Panel>
    {closeTarget && <ConfirmationDialog confirmLabel="Close operating day" description="IMS will close this server day using synced sales and deductions, save this physical cash count, and disable the POS that opened it. Sales or stock changes that existed only in the uninstalled app cannot be recovered. Only continue after checking the physical cash and recording why the phone is unavailable." onCancel={() => { if (!closing) setCloseTarget(null) }} onConfirm={() => void confirmAdminClose()} title={`Close ${closeTarget.business_date} from IMS?`} busy={closing} confirmDisabled={!Number.isFinite(Number(collectedCash)) || collectedCash.trim() === '' || Number(collectedCash) < 0 || closeReason.trim().length < 10}>
      <div className="space-y-4">
        <Notice tone="info">The closing will include only records already synced to IMS. Sales or stock changes remaining only in the uninstalled app will be missing.</Notice>
        <Input label="Physical cash counted (₱)" type="number" min="0" step="0.01" value={collectedCash} onChange={(event) => setCollectedCash(event.target.value)} />
        <Textarea label="Why is the POS unavailable?" rows={3} value={closeReason} onChange={(event) => setCloseReason(event.target.value)} placeholder="For example: development APK was uninstalled and its local queue is no longer available." />
      </div>
    </ConfirmationDialog>}
  </div>
}
