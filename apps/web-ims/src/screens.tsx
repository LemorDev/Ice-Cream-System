import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type { DbClient } from './lib/api'
import { addInventoryEntry, archiveProduct, createCategory, createDeviceActivation, createManagedStall, getOverheadForStall, getStockByProduct, listManagedUsers, reverseTransaction, saveManagedUser, saveProduct, setOwnerStalls, updateStall } from './lib/api'
import type { WebView } from './lib/access'
import { getDashboardMetrics, getDailyProfitReport, calculateDailyOverhead, DEFAULT_OVERHEAD_ITEMS, getBusinessDateKey, getProductPerformance, getRevenueTrend, shiftDateKey } from './lib/dashboard'
import { downloadCsv } from './lib/export'
import type { ManagedUser, OverheadItem, Product, Stall, WorkspaceData } from './lib/types'
import { Badge, Button, EmptyState, Input, Notice, OverheadIcon, Panel, Select, Table, TableCell, TableHead, Textarea } from './components/ui'
import { HorizontalBarChart, RevenueTrendChart } from './components/charts'

const peso = new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' })
const dateTime = new Intl.DateTimeFormat('en-PH', { dateStyle: 'medium', timeStyle: 'short' })

export type ScreenProps = {
  client: DbClient
  data: WorkspaceData
  onRefresh: () => Promise<void>
  onError: (message: string) => void
  stalls: Stall[]
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'The operation could not be completed.'
}

export function OverviewScreen({ data, onNavigate }: ScreenProps & { onNavigate: (view: string) => void }) {
  const today = new Date().toISOString().slice(0, 10)
  const metrics = getDashboardMetrics(data.products, data.inventory, data.transactions, today)
  const stock = metrics.stock
  const lowStock = data.products.filter((product) => metrics.lowStock.includes(product.id))

  const cards = [
    { label: 'Sales today', value: peso.format(metrics.sales), detail: `${metrics.completedSales} completed sale${metrics.completedSales === 1 ? '' : 's'}`, action: () => onNavigate('reports') },
    { label: 'Low-stock items', value: String(lowStock.length), detail: lowStock.length ? 'Review stock levels' : 'Everything is above threshold', action: () => onNavigate('adjustments') },
    { label: 'Active products', value: String(data.products.filter((product) => product.is_sellable).length), detail: `${data.products.length} total catalog items`, action: () => onNavigate('products') },
    { label: 'Transactions', value: String(data.transactions.length), detail: 'Latest 1,000 records loaded', action: () => onNavigate('transactions') },
  ]

  return <div className="space-y-6"><div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{cards.map((card) => <button key={card.label} className="rounded-2xl border border-[#eadcff] bg-white p-5 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-[#caa8ff]" onClick={card.action}><p className="text-sm text-slate-500">{card.label}</p><p className="mt-2 text-2xl font-bold text-slate-900">{card.value}</p><p className="mt-1 text-xs text-slate-400">{card.detail}</p></button>)}</div><div className="grid gap-6 lg:grid-cols-[1.2fr_0.8fr]"><Panel title="Stock watchlist" description="Products at or below their configured threshold."><Table><TableHead><th className="px-3 py-3">Product</th><th className="px-3 py-3">On hand</th><th className="px-3 py-3">Threshold</th><th className="px-3 py-3">Status</th></TableHead><tbody>{lowStock.slice(0, 8).map((product) => { const onHand = stock[product.id] ?? 0; return <tr key={product.id} className="border-b border-slate-100"><TableCell><p className="font-medium">{product.name}</p><p className="text-xs text-slate-400">{product.sku}</p></TableCell><TableCell>{onHand.toLocaleString()} {product.unit}</TableCell><TableCell>{product.low_stock_threshold.toLocaleString()} {product.unit}</TableCell><TableCell><Badge tone={onHand <= 0 ? 'danger' : 'warning'}>{onHand <= 0 ? 'Out of stock' : 'Low stock'}</Badge></TableCell></tr> })}</tbody></Table>{lowStock.length === 0 && <EmptyState title="No stock alerts" description="All active products are above their configured thresholds." />}</Panel><Panel title="Quick actions" description="Common selected-stall operations."><div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-1">{[['Receive stock', 'receiving'], ['Adjust inventory', 'adjustments'], ['Edit prices', 'pricing'], ['Download sales report', 'reports']].map(([label, view]) => <Button key={view} variant="secondary" className="text-left" onClick={() => onNavigate(view)}>{label}</Button>)}</div></Panel></div></div>
}

export function OwnerDashboardScreen({ data, onNavigate }: ScreenProps & { onNavigate: (view: WebView) => void }) {
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
          <p className="mt-1 text-4xl font-black tracking-tight text-[#fff8ea] sm:text-5xl">{peso.format(todayPoint.revenue)}</p>
          <p className="mt-2 text-xs text-[#e7d9f8]">{todayPoint.orders} completed order{todayPoint.orders === 1 ? '' : 's'}{change === null ? '' : ` · ${change >= 0 ? '+' : ''}${change.toFixed(0)}% vs yesterday`}</p>
        </div>
      </section>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {[['Orders today', todayPoint.orders.toLocaleString(), 'Completed sales'], ['7-day revenue', peso.format(weekRevenue), `${weekOrders} orders`], ['Average sale', peso.format(averageSale), 'Last 7 days'], ['Top product', products[0]?.name ?? '—', products[0] ? `${products[0].unitsSold.toLocaleString()} units` : 'No sales yet']].map(([label, value, detail]) => <div className="rounded-2xl border border-[#eadcff] bg-white p-4 shadow-sm" key={label}><p className="text-xs font-semibold text-slate-500">{label}</p><p className="mt-2 truncate text-xl font-black text-[#220046]">{value}</p><p className="mt-1 text-xs text-slate-400">{detail}</p></div>)}
      </div>

      <Panel title="Revenue trend" description="Completed sales over the last seven days." action={<Button variant="ghost" onClick={() => onNavigate('reports')}>View analytics</Button>}>
        <RevenueTrendChart points={trend.map((point) => ({ date: point.date, value: point.revenue }))} formatValue={(value) => peso.format(value)} />
      </Panel>

      <div className="grid gap-5 lg:grid-cols-2">
        <Panel title="Top products" description="Highest product revenue in the last seven days." action={<Button variant="ghost" onClick={() => onNavigate('productReport')}>Full report</Button>}>
          <HorizontalBarChart items={products.slice(0, 5).map((product) => ({ label: product.name, value: product.revenue, detail: `${product.unitsSold.toLocaleString()} units` }))} formatValue={(value) => peso.format(value)} />
        </Panel>
        <Panel title="Recent sales" description="Latest POS activity after synchronization.">
          {recentSales.length === 0 ? <EmptyState title="No sales yet" description="Transactions will appear after the POS completes and syncs a sale." /> : <div className="divide-y divide-slate-100">{recentSales.map((transaction) => <div className="flex items-center justify-between gap-3 py-3 first:pt-0 last:pb-0" key={transaction.id}><div className="min-w-0"><p className="truncate text-sm font-semibold text-[#39235f]">{transaction.receipt_number}</p><p className="mt-0.5 text-xs text-slate-400">{dateTime.format(new Date(transaction.occurred_at))}</p></div><div className="text-right"><p className="text-sm font-bold text-[#220046]">{peso.format(transaction.total_amount)}</p><Badge tone={transaction.status === 'completed' ? 'success' : 'warning'}>{transaction.status}</Badge></div></div>)}</div>}
        </Panel>
      </div>
    </div>
  )
}

export function SystemAdminOverviewScreen({ client, data, stalls, onError, onNavigate }: ScreenProps & { onNavigate: (view: WebView) => void }) {
  const [directory, setDirectory] = useState<ManagedUser[]>([])
  const [loadingDirectory, setLoadingDirectory] = useState(true)
  const stallKey = stalls.map((stall) => stall.id).join(',')

  useEffect(() => {
    let active = true
    setLoadingDirectory(true)
    void Promise.all(stalls.map((stall) => listManagedUsers(client, stall.id)))
      .then((usersByStall) => {
        if (!active) return
        const uniqueUsers = new Map(usersByStall.flat().map((user) => [user.id, user]))
        setDirectory([...uniqueUsers.values()])
      })
      .catch((error) => { if (active) onError(getErrorMessage(error)) })
      .finally(() => { if (active) setLoadingDirectory(false) })
    return () => { active = false }
  }, [client, onError, stallKey, stalls])

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

      <div className="grid gap-6 lg:grid-cols-2">
        <Panel title="System Administrator access" description="Platform-level responsibilities across every stall.">
          <ul className="space-y-3 text-sm text-slate-600">
            {['View and select every active stall', 'Create stalls and maintain stall identity', 'Create Owner or Cashier accounts', 'Assign Owners to one or several stalls', 'Perform operational support for any stall'].map((item) => <li className="flex gap-3" key={item}><span className="mt-0.5 text-emerald-600">✓</span><span>{item}</span></li>)}
          </ul>
        </Panel>
        <Panel title="Owner monitoring" description="Owners receive a read-only phone workspace scoped by assignment.">
          <ul className="space-y-3 text-sm text-slate-600">
            {['View revenue, profit, and sales activity for assigned stalls', 'Monitor product performance and operating days', 'Cannot change products, stock, prices, staff, POS devices, or stall settings', 'Cannot assign stalls or open another stall’s records'].map((item) => <li className="flex gap-3" key={item}><span className="mt-0.5 text-[#7c3aed]">•</span><span>{item}</span></li>)}
          </ul>
        </Panel>
      </div>
    </div>
  )
}

export function StallScreen({ client, data, onRefresh, onError }: ScreenProps) {
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
      setMessage('Stall created. Assign an Owner from Users & access.')
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
              <Input label="Stall code" hint="Used when identifying the stall during setup" value={code} onChange={(event) => setCode(event.target.value)} required />
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
                <p className="text-2xl font-black text-[#f5d68c]">{peso.format(totalDailyOverhead)} / day</p>
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

const emptyProduct = { name: '', unit: 'piece', category_id: '', sale_price: '0', cost_price: '0', low_stock_threshold: '0', pack_size: '1', conversion_rate: '1', is_sellable: true }
type ProductFormValues = typeof emptyProduct

function ProductFormFields({ form, setField, categories }: { form: ProductFormValues; setField: (field: keyof ProductFormValues, value: string | boolean) => void; categories: WorkspaceData['categories'] }) {
  return <><Input label="Product name" value={form.name} onChange={(event) => setField('name', event.target.value)} required autoFocus /><p className="rounded-xl border border-[#eadcff] bg-[#fbf7ff] px-3 py-2 text-sm text-[#4b2a7a]"><span className="font-semibold">SKU:</span> Generated automatically when you save this product.</p><div className="grid grid-cols-1 gap-3 sm:grid-cols-2"><Input label="Unit" value={form.unit} onChange={(event) => setField('unit', event.target.value)} required /><Select label="Category" value={form.category_id} onChange={(event) => setField('category_id', event.target.value)}><option value="">Uncategorized</option>{categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</Select></div><div className="grid grid-cols-1 gap-3 sm:grid-cols-2"><Input label="Sale price" type="number" min="0" step="0.01" value={form.sale_price} onChange={(event) => setField('sale_price', event.target.value)} /><Input label="Cost price" type="number" min="0" step="0.01" value={form.cost_price} onChange={(event) => setField('cost_price', event.target.value)} /></div><div className="grid grid-cols-1 gap-3 sm:grid-cols-2"><Input label="Low-stock threshold" type="number" min="0" step="0.001" value={form.low_stock_threshold} onChange={(event) => setField('low_stock_threshold', event.target.value)} /><Input label="Pack size" type="number" min="0.001" step="0.001" value={form.pack_size} onChange={(event) => setField('pack_size', event.target.value)} /></div><Input label="Conversion rate" hint="Usable units per pack" type="number" min="0.001" step="0.001" value={form.conversion_rate} onChange={(event) => setField('conversion_rate', event.target.value)} /><label className="flex items-center gap-2 text-sm text-slate-600"><input type="checkbox" checked={form.is_sellable} onChange={(event) => setField('is_sellable', event.target.checked)} /> Available for sale</label></>
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
  const [message, setMessage] = useState('')

  const refreshUsers = useCallback(async () => {
    if (!stallId) return
    setLoading(true)
    try { setUsers(await listManagedUsers(client, stallId)) }
    catch (error) { onError(getErrorMessage(error)) }
    finally { setLoading(false) }
  }, [client, onError, stallId])

  useEffect(() => { void refreshUsers() }, [refreshUsers])

  function resetForm() {
    setEditingId(undefined); setEmail(''); setDisplayName(''); setUserRole('cashier')
    setPassword(''); setIsActive(true); setAssignedStalls(stallId ? [stallId] : [])
  }

  useEffect(() => {
    setEditingId(undefined); setEmail(''); setDisplayName(''); setUserRole('cashier')
    setPassword(''); setIsActive(true); setAssignedStalls(stallId ? [stallId] : [])
    setActivationCode(''); setMessage('')
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
      setMessage('A new activation code was created. Redeeming it will deactivate the previous POS for this stall.')
    } catch (error) { onError(getErrorMessage(error)) }
    finally { setSavingDirectory(false) }
  }

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
      <div className="mt-6">{loading ? <p className="text-sm text-slate-500">Loading staff…</p> : users.length === 0 ? <EmptyState title="No staff accounts" description="Create the first account for this stall." /> : <Table><TableHead><th className="px-3 py-3">Name</th><th className="px-3 py-3">Role</th><th className="px-3 py-3">Status</th><th className="px-3 py-3" /></TableHead><tbody>{users.map((user) => <tr className="border-b border-slate-100" key={user.id}><TableCell><p className="font-medium">{user.display_name}</p><p className="text-xs text-slate-500">{user.email}</p></TableCell><TableCell>{user.role === 'owner' ? 'Owner' : 'Cashier'}</TableCell><TableCell><Badge tone={user.is_active ? 'success' : 'danger'}>{user.is_active ? 'Active' : 'Inactive'}</Badge></TableCell><TableCell><Button variant="ghost" onClick={() => editUser(user)}>Edit</Button></TableCell></tr>)}</tbody></Table>}</div>
    </Panel>
    <Panel title="Cashier POS activation" description="Generate a one-time code for this stall's Android POS. Redeeming a replacement code deactivates the previous device.">
      <div className="grid max-w-xl gap-3 sm:grid-cols-[1fr_auto]"><Input label="Device name" value={deviceName} onChange={(event) => setDeviceName(event.target.value)} /><Button className="self-end" disabled={savingDirectory} onClick={() => void createActivation()}>{savingDirectory ? 'Creating…' : 'Create activation code'}</Button></div>
      {activationCode && <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-4"><p className="text-sm text-amber-800">Enter this code once on the Android POS:</p><p className="mt-1 font-mono text-2xl font-black tracking-widest text-amber-950">{activationCode}</p><p className="mt-1 text-xs text-amber-700">This code will not be shown again.</p></div>}
    </Panel>
  </div>
}

export function ProductsScreen({ client, data, onRefresh, onError }: ScreenProps) {
  const [form, setForm] = useState(emptyProduct)
  const [editingId, setEditingId] = useState<string>()
  const [productModalOpen, setProductModalOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [message, setMessage] = useState('')
  const [categoryName, setCategoryName] = useState('')
  const [saving, setSaving] = useState(false)
  const stock = getStockByProduct(data.inventory)
  const filtered = data.products.filter((product) => `${product.name} ${product.sku}`.toLowerCase().includes(search.toLowerCase()))
  function setField(field: keyof typeof emptyProduct, value: string | boolean) { setForm((current) => ({ ...current, [field]: value })) }
  function openAddProduct() { setEditingId(undefined); setForm(emptyProduct); setMessage(''); setProductModalOpen(true) }
  function edit(product: Product) { setEditingId(product.id); setForm({ name: product.name, unit: product.unit, category_id: product.category_id ?? '', sale_price: String(product.sale_price), cost_price: String(product.cost_price), low_stock_threshold: String(product.low_stock_threshold), pack_size: String(product.pack_size), conversion_rate: String(product.conversion_rate), is_sellable: product.is_sellable }); setMessage(''); setProductModalOpen(true) }
  const closeProductModal = useCallback(() => { if (saving) return; setProductModalOpen(false); setEditingId(undefined); setForm(emptyProduct); setMessage('') }, [saving])
  async function submit(event: FormEvent) {
    event.preventDefault(); setMessage('')
    const values = { ...form, name: form.name.trim(), unit: form.unit.trim() }
    const numbers = ['sale_price', 'cost_price', 'low_stock_threshold', 'pack_size', 'conversion_rate'].map((field) => Number(form[field as keyof typeof form]))
    if (!values.name || !values.unit || numbers.some((value) => !Number.isFinite(value) || value < 0) || numbers[3] <= 0 || numbers[4] <= 0) { setMessage('Complete all product fields with valid non-negative values. Pack size and conversion rate must be greater than zero.'); return }
    setSaving(true)
    try { await saveProduct(client, { stall_id: data.stall?.id ?? '', category_id: values.category_id || null, name: values.name, unit: values.unit, sale_price: numbers[0], cost_price: numbers[1], low_stock_threshold: numbers[2], pack_size: numbers[3], conversion_rate: numbers[4], is_sellable: form.is_sellable }, editingId); await onRefresh(); closeProductModal() } catch (error) { onError(getErrorMessage(error)) } finally { setSaving(false) }
  }
  async function addNewCategory(event: FormEvent) { event.preventDefault(); if (!categoryName.trim() || !data.stall) return; try { await createCategory(client, { stall_id: data.stall.id, name: categoryName.trim(), sort_order: data.categories.length }); setCategoryName(''); await onRefresh() } catch (error) { onError(getErrorMessage(error)) } }
  async function archive(product: Product) { if (!window.confirm(`Archive ${product.name}? It will no longer be sellable.`)) return; try { await archiveProduct(client, product.id); await onRefresh() } catch (error) { onError(getErrorMessage(error)) } }
  useEffect(() => {
    if (!productModalOpen) return
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') closeProductModal() }
    document.addEventListener('keydown', closeOnEscape)
    document.body.style.overflow = 'hidden'
    return () => { document.removeEventListener('keydown', closeOnEscape); document.body.style.overflow = '' }
  }, [productModalOpen, closeProductModal])
  return <><Panel title="Product catalog" description="Manage menu items, packaging, prices, thresholds, and conversions." action={<div className="flex flex-col gap-2 sm:flex-row"><Input aria-label="Search products" placeholder="Search name or SKU" value={search} onChange={(event) => setSearch(event.target.value)} /><Button className="shrink-0" onClick={openAddProduct}>Add product</Button></div>}>
    {filtered.length === 0 ? <EmptyState title="No products found" description={search ? 'Try a different search.' : 'Add your first product to begin building the catalog.'} /> : <Table><TableHead><th className="px-3 py-3">Product</th><th className="px-3 py-3">Price</th><th className="px-3 py-3">On hand</th><th className="px-3 py-3">Status</th><th className="px-3 py-3" /></TableHead><tbody>{filtered.map((product) => { const onHand = stock[product.id] ?? 0; return <tr key={product.id} className="border-b border-slate-100"><TableCell><p className="font-medium">{product.name}</p><p className="text-xs text-slate-400">{product.sku} · {product.unit}</p></TableCell><TableCell>{peso.format(product.sale_price)}</TableCell><TableCell>{onHand.toLocaleString()}</TableCell><TableCell><Badge tone={!product.is_sellable ? 'neutral' : onHand <= product.low_stock_threshold ? 'warning' : 'success'}>{!product.is_sellable ? 'Archived' : onHand <= product.low_stock_threshold ? 'Low stock' : 'Active'}</Badge></TableCell><TableCell><div className="flex gap-2"><Button variant="ghost" onClick={() => edit(product)}>Edit</Button>{product.is_sellable && <Button variant="danger" onClick={() => void archive(product)}>Archive</Button>}</div></TableCell></tr> })}</tbody></Table>}
  </Panel>{productModalOpen && <div className="fixed inset-0 z-50 flex items-end bg-[#18002f]/55 p-0 backdrop-blur-[2px] sm:items-center sm:justify-center sm:p-6" onMouseDown={(event) => { if (event.target === event.currentTarget) closeProductModal() }}><section aria-labelledby="product-modal-title" aria-modal="true" className="max-h-[92vh] w-full overflow-y-auto rounded-t-3xl bg-[#fffdf8] shadow-2xl sm:max-w-2xl sm:rounded-3xl" role="dialog"><div className="sticky top-0 flex items-start justify-between gap-4 border-b border-[#eadcff] bg-[#fffdf8]/95 p-5 backdrop-blur"><div><p className="text-xs font-semibold uppercase tracking-[0.18em] text-[#5a1bb0]">Product catalog</p><h2 id="product-modal-title" className="mt-1 text-xl font-black text-[#220046]">{editingId ? 'Edit product' : 'Add product'}</h2><p className="mt-1 text-sm text-slate-500">Prices and conversions are used by the POS and reports.</p></div><button aria-label="Close product form" className="rounded-lg p-2 text-[#4b2a7a] transition hover:bg-[#f5ebff]" onClick={closeProductModal} type="button">×</button></div><form className="space-y-4 p-5" onSubmit={submit}><ProductFormFields categories={data.categories} form={form} setField={setField} />{message && <Notice>{message}</Notice>}<div className="flex flex-wrap justify-end gap-2 border-t border-slate-100 pt-4"><Button type="button" variant="ghost" onClick={closeProductModal}>Cancel</Button><Button disabled={saving}>{saving ? 'Saving…' : editingId ? 'Save changes' : 'Add product'}</Button></div></form><form className="border-t border-slate-100 p-5" onSubmit={addNewCategory}><p className="text-sm font-semibold text-[#39235f]">Add a category</p><div className="mt-2 flex flex-col gap-2 sm:flex-row"><Input aria-label="New category name" placeholder="e.g. Ice cream" value={categoryName} onChange={(event) => setCategoryName(event.target.value)} /><Button type="submit">Add category</Button></div></form></section></div>}</>
}

function InventoryEntryForm({ mode, client, data, onRefresh, onError }: ScreenProps & { mode: 'receive' | 'adjust' }) {
  const [productId, setProductId] = useState('')
  const [quantity, setQuantity] = useState('')
  const [direction, setDirection] = useState<'add' | 'remove'>('add')
  const [reason, setReason] = useState(mode === 'receive' ? 'Stock delivery' : '')
  const [message, setMessage] = useState('')
  const [saving, setSaving] = useState(false)
  const stock = getStockByProduct(data.inventory)
  async function submit(event: FormEvent) { event.preventDefault(); setMessage(''); const qty = Number(quantity); const product = data.products.find((item) => item.id === productId); const delta = mode === 'receive' || direction === 'add' ? qty : -qty; if (!product || !Number.isFinite(qty) || qty <= 0 || !reason.trim()) { setMessage('Select a product, enter a quantity greater than zero, and provide a reason.'); return } if (mode === 'adjust' && delta < 0 && (stock[product.id] ?? 0) + delta < 0) { setMessage('This adjustment would make stock negative.'); return } setSaving(true); try { await addInventoryEntry(client, { stall_id: data.stall?.id ?? '', product_id: product.id, quantity_delta: delta, movement_type: mode === 'receive' ? 'receive' : 'adjustment', reason: reason.trim() }); setQuantity(''); setMessage(`${mode === 'receive' ? 'Stock received' : 'Inventory adjusted'} successfully.`); await onRefresh() } catch (error) { onError(getErrorMessage(error)) } finally { setSaving(false) } }
  return <Panel title={mode === 'receive' ? 'Receive stock' : 'Adjust inventory'} description={mode === 'receive' ? 'Record deliveries as positive ledger entries.' : 'Correct stock counts while protecting against negative inventory.'}><form className="max-w-xl space-y-4" onSubmit={submit}><Select label="Product" value={productId} onChange={(event) => setProductId(event.target.value)} required><option value="">Choose a product</option>{data.products.map((product) => <option key={product.id} value={product.id}>{product.name} ({product.unit}) · on hand {(stock[product.id] ?? 0).toLocaleString()}</option>)}</Select>{mode === 'adjust' && <Select label="Adjustment direction" value={direction} onChange={(event) => setDirection(event.target.value as 'add' | 'remove')}><option value="add">Add stock</option><option value="remove">Remove stock</option></Select>}<Input label="Quantity" type="number" min="0.001" step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} required /><Textarea label="Reason" rows={3} placeholder="Supplier delivery, count correction, damaged stock…" value={reason} onChange={(event) => setReason(event.target.value)} required />{message && <Notice>{message}</Notice>}<Button disabled={saving}>{saving ? 'Saving…' : mode === 'receive' ? 'Record delivery' : 'Save adjustment'}</Button></form></Panel>
}

export function ReceivingScreen(props: ScreenProps) { return <InventoryEntryForm {...props} mode="receive" /> }
export function AdjustmentsScreen(props: ScreenProps) { return <InventoryEntryForm {...props} mode="adjust" /> }

export function PricingScreen({ client, data, onRefresh, onError }: ScreenProps) {
  const [drafts, setDrafts] = useState<Record<string, { sale_price: string; cost_price: string; pack_size: string; conversion_rate: string }>>({})
  const [savingId, setSavingId] = useState<string>()
  function draft(product: Product) { return drafts[product.id] ?? { sale_price: String(product.sale_price), cost_price: String(product.cost_price), pack_size: String(product.pack_size), conversion_rate: String(product.conversion_rate) } }
  async function save(product: Product) { const values = draft(product); const numbers = [values.sale_price, values.cost_price, values.pack_size, values.conversion_rate].map(Number); if (numbers.some((value) => !Number.isFinite(value) || value < 0) || numbers[2] <= 0 || numbers[3] <= 0) { onError('Enter valid prices and positive pack/conversion values.'); return } setSavingId(product.id); try { await saveProduct(client, { stall_id: product.stall_id, category_id: product.category_id, sku: product.sku, name: product.name, unit: product.unit, sale_price: numbers[0], cost_price: numbers[1], low_stock_threshold: product.low_stock_threshold, pack_size: numbers[2], conversion_rate: numbers[3], is_sellable: product.is_sellable }, product.id); await onRefresh() } catch (error) { onError(getErrorMessage(error)) } finally { setSavingId(undefined) } }
  return <Panel title="Price and conversion management" description="Update retail prices, raw costs, and pack-to-usable-unit conversions without an app release.">{data.products.length === 0 ? <EmptyState title="No products yet" description="Add products before changing their prices." /> : <Table><TableHead><th className="px-3 py-3">Product</th><th className="px-3 py-3">Sale price</th><th className="px-3 py-3">Cost price</th><th className="px-3 py-3">Pack size</th><th className="px-3 py-3">Usable units / pack</th><th className="px-3 py-3" /></TableHead><tbody>{data.products.map((product) => { const values = draft(product); const set = (field: keyof typeof values, value: string) => setDrafts((current) => ({ ...current, [product.id]: { ...values, [field]: value } })); return <tr key={product.id} className="border-b border-slate-100"><TableCell><p className="font-medium">{product.name}</p><p className="text-xs text-slate-400">{product.unit}</p></TableCell><TableCell><input className="w-28 rounded border border-slate-300 px-2 py-1" type="number" min="0" step="0.01" value={values.sale_price} onChange={(event) => set('sale_price', event.target.value)} /></TableCell><TableCell><input className="w-28 rounded border border-slate-300 px-2 py-1" type="number" min="0" step="0.01" value={values.cost_price} onChange={(event) => set('cost_price', event.target.value)} /></TableCell><TableCell><input className="w-24 rounded border border-slate-300 px-2 py-1" type="number" min="0.001" step="0.001" value={values.pack_size} onChange={(event) => set('pack_size', event.target.value)} /></TableCell><TableCell><input className="w-28 rounded border border-slate-300 px-2 py-1" type="number" min="0.001" step="0.001" value={values.conversion_rate} onChange={(event) => set('conversion_rate', event.target.value)} /></TableCell><TableCell><Button disabled={savingId === product.id} onClick={() => void save(product)}>{savingId === product.id ? 'Saving…' : 'Save'}</Button></TableCell></tr> })}</tbody></Table>}</Panel>
}

export function TransactionsScreen({ client, data, onRefresh, onError }: ScreenProps) {
  const [status, setStatus] = useState('all')
  const [search, setSearch] = useState('')
  const [selectedId, setSelectedId] = useState<string>()
  const [reason, setReason] = useState('Customer changed mind')
  const [restock, setRestock] = useState(true)
  const filtered = data.transactions.filter((transaction) => (status === 'all' || transaction.status === status) && transaction.receipt_number.toLowerCase().includes(search.toLowerCase()))
  async function reverse() { if (!selectedId || !reason.trim()) return; try { await reverseTransaction(client, selectedId, reason.trim(), restock); setSelectedId(undefined); await onRefresh() } catch (error) { onError(getErrorMessage(error)) } }
  return <Panel title="Transaction history" description="Review sales and reverse an eligible transaction with an audit reason." action={<div className="flex gap-2"><Input aria-label="Search receipts" placeholder="Receipt number" value={search} onChange={(event) => setSearch(event.target.value)} /><Select aria-label="Filter status" value={status} onChange={(event) => setStatus(event.target.value)}><option value="all">All statuses</option><option value="completed">Completed</option><option value="voided">Voided</option><option value="refunded">Refunded</option></Select></div>}>{filtered.length === 0 ? <EmptyState title="No transactions found" description="Sales will appear here after the POS syncs them." /> : <Table><TableHead><th className="px-3 py-3">Receipt</th><th className="px-3 py-3">Date</th><th className="px-3 py-3">Total</th><th className="px-3 py-3">Status</th><th className="px-3 py-3" /></TableHead><tbody>{filtered.map((transaction) => <tr key={transaction.id} className="border-b border-slate-100"><TableCell className="font-medium">{transaction.receipt_number}</TableCell><TableCell>{dateTime.format(new Date(transaction.occurred_at))}</TableCell><TableCell>{peso.format(transaction.total_amount)}</TableCell><TableCell><Badge tone={transaction.status === 'completed' ? 'success' : transaction.status === 'voided' ? 'danger' : 'warning'}>{transaction.status}</Badge></TableCell><TableCell>{transaction.status === 'completed' && <Button variant="danger" onClick={() => setSelectedId(transaction.id)}>Void / reverse</Button>}</TableCell></tr>)}</tbody></Table>}{selectedId && <div className="mt-5 rounded-xl border border-red-200 bg-red-50 p-4"><p className="font-semibold text-red-800">Reverse transaction</p><p className="mt-1 text-sm text-red-700">Choose whether the sold stock should return to inventory.</p><div className="mt-3 grid gap-3 sm:grid-cols-2"><Select label="Reason" value={reason} onChange={(event) => setReason(event.target.value)}><option>Customer changed mind</option><option>Incorrect order</option><option>Quality issue</option></Select><Select label="Stock action" value={restock ? 'restock' : 'waste'} onChange={(event) => setRestock(event.target.value === 'restock')}><option value="restock">Restock items</option><option value="waste">Waste stock</option></Select></div><div className="mt-3 flex gap-2"><Button variant="danger" onClick={() => void reverse()}>Confirm reversal</Button><Button variant="ghost" onClick={() => setSelectedId(undefined)}>Cancel</Button></div></div>}</Panel>
}

export function ProductPerformanceScreen({ data }: ScreenProps) {
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
          {[['Product revenue', peso.format(productRevenue)], ['Units sold', unitsSold.toLocaleString()], ['Products sold', products.length.toLocaleString()], ['Top product share', `${topShare.toFixed(0)}%`]].map(([label, value]) => <div className="rounded-2xl bg-[#f7f1ff] p-4" key={label}><p className="text-xs font-semibold text-[#6b4d89]">{label}</p><p className="mt-2 text-xl font-black text-[#220046]">{value}</p></div>)}
        </div>
      </Panel>

      <Panel title="Top products by revenue" description={`${from} to ${to}`}>
        <HorizontalBarChart items={products.slice(0, 8).map((product) => ({ label: product.name, value: product.revenue, detail: `${product.unitsSold.toLocaleString()} units · ${product.orders} orders` }))} formatValue={(value) => peso.format(value)} />
      </Panel>

      <Panel title="Product detail" description="Completed POS sales in the selected period.">
        {products.length === 0 ? <EmptyState title="No product sales" description="Try a wider date range or wait for completed sales to sync." /> : <>
          <div className="space-y-3 md:hidden">{products.map((product, index) => <article className="rounded-2xl border border-[#eadcff] bg-[#fffdf8] p-4" key={product.name}><div className="flex items-start justify-between gap-3"><div><p className="text-xs font-bold text-[#8b6ca8]">#{index + 1}</p><h3 className="mt-1 font-bold text-[#220046]">{product.name}</h3></div><p className="font-black text-[#5a1bb0]">{peso.format(product.revenue)}</p></div><div className="mt-3 grid grid-cols-2 gap-2 text-xs text-slate-500"><span>{product.unitsSold.toLocaleString()} units sold</span><span className="text-right">{product.orders} orders</span></div></article>)}</div>
          <div className="hidden md:block"><Table><TableHead><th className="px-3 py-3">Rank</th><th className="px-3 py-3">Product</th><th className="px-3 py-3 text-right">Units sold</th><th className="px-3 py-3 text-right">Orders</th><th className="px-3 py-3 text-right">Revenue</th></TableHead><tbody>{products.map((product, index) => <tr className="border-b border-slate-100" key={product.name}><TableCell>#{index + 1}</TableCell><TableCell className="font-semibold">{product.name}</TableCell><TableCell className="text-right">{product.unitsSold.toLocaleString()}</TableCell><TableCell className="text-right">{product.orders}</TableCell><TableCell className="text-right font-bold text-[#5a1bb0]">{peso.format(product.revenue)}</TableCell></tr>)}</tbody></Table></div>
        </>}
      </Panel>
    </div>
  )
}

export function ReportsScreen({ data }: ScreenProps) {
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
      netProfit: acc.netProfit + day.netProfit,
    }),
    { revenue: 0, cogs: 0, wasteCost: 0, overhead: 0, netProfit: 0 },
  ), [profitDays])
  const averageSale = report.completed.length > 0 ? totals.revenue / report.completed.length : 0

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
      <Panel title="Sales analytics" description="Revenue and profit monitoring for completed POS sales." action={<div className="grid w-full grid-cols-2 gap-2 sm:w-auto sm:grid-cols-[minmax(0,9rem)_minmax(0,9rem)_auto] sm:items-end"><Input label="From" type="date" value={from} onChange={(event) => setFrom(event.target.value)} /><Input label="To" type="date" value={to} onChange={(event) => setTo(event.target.value)} /><Button className="col-span-2 w-full sm:col-span-1 sm:w-auto" onClick={exportReport} disabled={report.transactions.length === 0}>Download CSV</Button></div>}>
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <div className="rounded-2xl bg-[#f4ecff] p-4"><p className="text-xs font-semibold text-[#5a1bb0]">Revenue</p><p className="mt-2 text-xl font-black text-[#220046] sm:text-2xl">{peso.format(totals.revenue)}</p><p className="mt-1 text-xs text-slate-500">{report.completed.length} completed sales</p></div>
          <div className={`rounded-2xl p-4 ${totals.netProfit >= 0 ? 'bg-emerald-50' : 'bg-red-50'}`}><p className={`text-xs font-semibold ${totals.netProfit >= 0 ? 'text-emerald-700' : 'text-red-700'}`}>Net profit</p><p className={`mt-2 text-xl font-black sm:text-2xl ${totals.netProfit >= 0 ? 'text-emerald-900' : 'text-red-900'}`}>{peso.format(totals.netProfit)}</p><p className="mt-1 text-xs text-slate-500">After reported costs</p></div>
          <div className="rounded-2xl bg-[#fff7e8] p-4"><p className="text-xs font-semibold text-amber-700">Average sale</p><p className="mt-2 text-xl font-black text-amber-950 sm:text-2xl">{peso.format(averageSale)}</p><p className="mt-1 text-xs text-slate-500">Per completed order</p></div>
          <div className="rounded-2xl bg-slate-100 p-4"><p className="text-xs font-semibold text-slate-600">Voided sales</p><p className="mt-2 text-xl font-black text-slate-900 sm:text-2xl">{report.voided.length}</p><p className="mt-1 text-xs text-slate-500">In selected period</p></div>
        </div>
      </Panel>

      <Panel title="Revenue by day" description="Completed sales in the selected period.">
        <RevenueTrendChart points={revenueTrend.map((day) => ({ date: day.date, value: day.revenue }))} formatValue={(value) => peso.format(value)} />
      </Panel>

      <div className="grid gap-5 lg:grid-cols-[0.8fr_1.2fr]">
        <Panel title="Cost summary" description="Amounts used in the profit estimate.">
          <HorizontalBarChart items={[
            { label: 'Cost of goods sold', value: totals.cogs },
            { label: 'Fixed overhead', value: totals.overhead },
            { label: 'Waste', value: totals.wasteCost },
          ]} formatValue={(value) => peso.format(value)} />
        </Panel>
        <Panel title="Recent activity" description="Latest transactions in this date range.">
          {report.transactions.length === 0 ? <EmptyState title="No transactions" description="Choose a different date range or wait for POS synchronization." /> : <div className="divide-y divide-slate-100">{report.transactions.slice(0, 8).map((transaction) => <div className="flex items-center justify-between gap-3 py-3 first:pt-0 last:pb-0" key={transaction.id}><div className="min-w-0"><p className="truncate text-sm font-semibold text-[#39235f]">{transaction.receipt_number}</p><p className="mt-0.5 text-xs text-slate-400">{dateTime.format(new Date(transaction.occurred_at))}</p></div><div className="text-right"><p className="text-sm font-bold">{peso.format(transaction.total_amount)}</p><Badge tone={transaction.status === 'completed' ? 'success' : 'warning'}>{transaction.status}</Badge></div></div>)}</div>}
        </Panel>
      </div>

      {profitDays.length > 0 && <Panel title="Daily breakdown" description="Revenue, costs, and net profit for each active day.">
        <div className="space-y-3 md:hidden">{profitDays.map((day) => <article className="rounded-2xl border border-[#eadcff] bg-[#fffdf8] p-4" key={day.businessDate}><div className="flex items-start justify-between gap-3"><div><p className="text-xs font-semibold text-slate-500">{day.businessDate}</p><p className="mt-1 text-lg font-black text-[#220046]">{peso.format(day.revenue)}</p><p className="text-xs text-slate-400">{day.completedSales} sales</p></div><p className={`font-black ${day.netProfit >= 0 ? 'text-emerald-700' : 'text-red-700'}`}>{peso.format(day.netProfit)}</p></div><div className="mt-3 grid grid-cols-3 gap-2 border-t border-slate-100 pt-3 text-xs text-slate-500"><span>COGS<br/><strong>{peso.format(day.cogs)}</strong></span><span>Waste<br/><strong>{peso.format(day.wasteCost)}</strong></span><span>Overhead<br/><strong>{peso.format(day.fixedOverhead)}</strong></span></div></article>)}</div>
        <div className="hidden md:block"><Table><TableHead><th className="px-3 py-3">Date</th><th className="px-3 py-3 text-right">Revenue</th><th className="px-3 py-3 text-right">COGS</th><th className="px-3 py-3 text-right">Waste</th><th className="px-3 py-3 text-right">Overhead</th><th className="px-3 py-3 text-right">Net profit</th><th className="px-3 py-3 text-right">Sales</th></TableHead><tbody>{profitDays.map((day) => <tr className="border-b border-slate-100" key={day.businessDate}><TableCell className="font-medium">{day.businessDate}</TableCell><TableCell className="text-right">{peso.format(day.revenue)}</TableCell><TableCell className="text-right">{peso.format(day.cogs)}</TableCell><TableCell className="text-right">{peso.format(day.wasteCost)}</TableCell><TableCell className="text-right">{peso.format(day.fixedOverhead)}</TableCell><TableCell className={`text-right font-semibold ${day.netProfit >= 0 ? 'text-emerald-700' : 'text-red-700'}`}>{peso.format(day.netProfit)}</TableCell><TableCell className="text-right">{day.completedSales}</TableCell></tr>)}</tbody></Table></div>
      </Panel>}
    </div>
  )
}

export function OperatingDaysScreen({ data }: ScreenProps) {
  const openDay = data.businessDays.find((day) => day.closed_at === null)
  return <div className="space-y-6">
    {openDay && <Notice tone="info">This stall is open. It was opened {dateTime.format(new Date(openDay.opened_at))}.</Notice>}
    <Panel title="Opening and closing history" description="Times are recorded by the Cashier POS and shown in your local time.">
      {data.businessDays.length === 0 ? <EmptyState title="No operating days yet" description="The first day will appear after a Cashier opens the Android POS and it syncs." /> : <>
        <div className="space-y-3 md:hidden">{data.businessDays.map((day) => <article className="rounded-2xl border border-[#eadcff] bg-[#fffdf8] p-4" key={day.id}><div className="flex items-start justify-between gap-3"><div><p className="text-xs font-semibold text-slate-500">Business date</p><p className="mt-1 font-black text-[#220046]">{day.business_date}</p></div>{day.closed_at ? <Badge tone="neutral">Closed</Badge> : <Badge tone="success">Open</Badge>}</div><dl className="mt-4 grid grid-cols-2 gap-3 text-xs"><div><dt className="text-slate-400">Opened</dt><dd className="mt-1 font-semibold text-slate-700">{dateTime.format(new Date(day.opened_at))}</dd></div><div><dt className="text-slate-400">Closed</dt><dd className="mt-1 font-semibold text-slate-700">{day.closed_at ? dateTime.format(new Date(day.closed_at)) : 'Still open'}</dd></div><div><dt className="text-slate-400">Closing cash</dt><dd className="mt-1 font-semibold text-slate-700">{day.closing_cash_total === null ? '—' : peso.format(day.closing_cash_total)}</dd></div><div><dt className="text-slate-400">Notes</dt><dd className="mt-1 font-semibold text-slate-700">{[day.opening_notes, day.closing_notes].filter(Boolean).join(' · ') || '—'}</dd></div></dl></article>)}</div>
        <div className="hidden md:block"><Table>
        <TableHead><th className="px-3 py-3">Business date</th><th className="px-3 py-3">Opened</th><th className="px-3 py-3">Closed</th><th className="px-3 py-3 text-right">Closing cash</th><th className="px-3 py-3">Notes</th></TableHead>
        <tbody>{data.businessDays.map((day) => <tr className="border-b border-slate-100" key={day.id}>
          <TableCell className="font-medium">{day.business_date}</TableCell>
          <TableCell>{dateTime.format(new Date(day.opened_at))}</TableCell>
          <TableCell>{day.closed_at ? dateTime.format(new Date(day.closed_at)) : <Badge tone="success">Open</Badge>}</TableCell>
          <TableCell className="text-right">{day.closing_cash_total === null ? '—' : peso.format(day.closing_cash_total)}</TableCell>
          <TableCell className="max-w-xs text-xs text-slate-500">{[day.opening_notes, day.closing_notes].filter(Boolean).join(' · ') || '—'}</TableCell>
        </tr>)}</tbody>
        </Table></div>
      </>}
    </Panel>
  </div>
}
