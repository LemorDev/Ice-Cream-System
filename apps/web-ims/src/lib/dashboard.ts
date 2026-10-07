import type { DailyProfitReport, DailyStoreClosing, InventoryEntry, OverheadItem, Product, RevenueDeduction, Transaction, TransactionItem } from './types'

export const DEFAULT_OVERHEAD_ITEMS: OverheadItem[] = [
  { key: 'cashier', label: 'Cashier pay', dailyRate: 500.0, icon: 'cashier', description: 'Daily cashier wage' },
  { key: 'rent', label: 'Stall rent', dailyRate: 200.0, icon: 'rent', description: 'Daily space/booth rental' },
  { key: 'electricity', label: 'Electricity', dailyRate: 33.33, icon: 'electricity', description: 'Power for freezers & lighting' },
  { key: 'water', label: 'Water', dailyRate: 10.0, icon: 'water', description: 'Sanitation & cleaning water' },
]

export const FIXED_OVERHEAD_ITEMS = DEFAULT_OVERHEAD_ITEMS

export function calculateDailyOverhead(items: OverheadItem[] = DEFAULT_OVERHEAD_ITEMS): number {
  return items.reduce((sum, item) => sum + (Number(item.dailyRate) || 0), 0)
}

export const DAILY_FIXED_OVERHEAD = calculateDailyOverhead(DEFAULT_OVERHEAD_ITEMS) // 743.33

export function transactionBusinessDate(transaction: Transaction): string {
  return transaction.business_date ?? getBusinessDateKey(transaction.occurred_at)
}

export function getBusinessDateKey(value: string | Date = new Date()): string {
  const date = typeof value === 'string' ? new Date(value) : value
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Manila', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

export function shiftDateKey(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}

export function formatDateRangeLabel(from: string, to: string): string {
  const dateKeyPattern = /^\d{4}-\d{2}-\d{2}$/
  if (!dateKeyPattern.test(from) || !dateKeyPattern.test(to) || from > to) return 'Selected period'
  const format = new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
  const start = format.format(new Date(`${from}T00:00:00Z`))
  const end = format.format(new Date(`${to}T00:00:00Z`))
  return from === to ? start : `${start} – ${end}`
}

export function getRevenueTrend(transactions: Transaction[], from: string, to: string) {
  const dateKeyPattern = /^\d{4}-\d{2}-\d{2}$/
  if (!dateKeyPattern.test(from) || !dateKeyPattern.test(to) || from > to) return []

  const totals = new Map<string, { revenue: number; orders: number }>()
  for (const transaction of transactions) {
    if (transaction.status !== 'completed') continue
    const date = transactionBusinessDate(transaction)
    if (date < from || date > to) continue
    const current = totals.get(date) ?? { revenue: 0, orders: 0 }
    current.revenue += transaction.total_amount
    current.orders += 1
    totals.set(date, current)
  }

  const points = []
  for (let date = from; date <= to; date = shiftDateKey(date, 1)) {
    points.push({ date, ...(totals.get(date) ?? { revenue: 0, orders: 0 }) })
  }
  return points
}

export function getProductPerformance(
  transactions: Transaction[], transactionItems: TransactionItem[], from: string, to: string,
) {
  const completed = new Map(
    transactions
      .filter((transaction) => transaction.status === 'completed')
      .filter((transaction) => {
        const date = transactionBusinessDate(transaction)
        return date >= from && date <= to
      })
      .map((transaction) => [transaction.id, transaction]),
  )
  const products = new Map<string, { name: string; unitsSold: number; revenue: number; orderIds: Set<string> }>()
  for (const item of transactionItems) {
    if (!completed.has(item.transaction_id)) continue
    const key = item.product_id ?? item.product_name
    const current = products.get(key) ?? { name: item.product_name, unitsSold: 0, revenue: 0, orderIds: new Set<string>() }
    current.unitsSold += item.quantity
    current.revenue += item.line_total
    current.orderIds.add(item.transaction_id)
    products.set(key, current)
  }
  return [...products.values()]
    .map((product) => ({
      name: product.name,
      unitsSold: product.unitsSold,
      revenue: product.revenue,
      orders: product.orderIds.size,
    }))
    .sort((a, b) => b.revenue - a.revenue || b.unitsSold - a.unitsSold)
}

export function getDashboardMetrics(products: Product[], inventory: InventoryEntry[], transactions: Transaction[], today: string) {
  const stock = inventory.reduce<Record<string, number>>((result, entry) => {
    result[entry.product_id] = (result[entry.product_id] ?? 0) + entry.quantity_delta
    return result
  }, {})
  const todayTransactions = transactions.filter((transaction) => transactionBusinessDate(transaction) === today)
  const completedSales = todayTransactions.filter((transaction) => transaction.status === 'completed')
  return {
    sales: completedSales.reduce((sum, transaction) => sum + transaction.total_amount, 0),
    completedSales: completedSales.length,
    lowStock: products.filter((product) => (stock[product.id] ?? 0) <= product.low_stock_threshold).map((product) => product.id),
    activeProducts: products.filter((product) => product.is_sellable).length,
    stock,
  }
}

/** Build a cost-price lookup from the product list keyed by product id. */
function buildCostMap(products: Product[]): Record<string, number> {
  return products.reduce<Record<string, number>>((map, product) => {
    map[product.id] = product.cost_price
    return map
  }, {})
}

/** Ledger deltas use base units; purchase costs are stored per pack. */
function buildBaseUnitCostMap(products: Product[]): Record<string, number> {
  return products.reduce<Record<string, number>>((map, product) => {
    const unitsPerPack = Number(product.pack_size) * Number(product.conversion_rate)
    map[product.id] = product.cost_price / (Number.isFinite(unitsPerPack) && unitsPerPack > 0 ? unitsPerPack : 1)
    return map
  }, {})
}

/**
 * Compute a daily profit report for a given date range.
 *
 * - Revenue  = sum of `total_amount` on completed transactions
 * - COGS     = saved sale-time cost for completed sales; legacy rows fall back to current catalog cost
 * - Waste    = cost of items in void_waste inventory entries + adjustment removals
 * - Overhead = configured daily fixed expenses for each operating day
 * - Profit   = Revenue − COGS − Waste − Overhead − POS deductions marked as additional expenses
 */
export function getDailyProfitReport(
  products: Product[],
  inventory: InventoryEntry[],
  transactions: Transaction[],
  transactionItems: TransactionItem[],
  from: string,
  to: string,
  overheadItems: OverheadItem[] = DEFAULT_OVERHEAD_ITEMS,
  operatingDates: string[] = [],
  reportResetAt: string | null = null,
  deductions: RevenueDeduction[] = [],
  closings: DailyStoreClosing[] = [],
): DailyProfitReport[] {
  const costMap = buildCostMap(products)
  const baseUnitCostMap = buildBaseUnitCostMap(products)
  const dailyOverhead = calculateDailyOverhead(overheadItems)
  const resetTime = reportResetAt ? Date.parse(reportResetAt) : NaN

  // Group by the operating day's opening date, including sales after midnight.
  const dateSet = new Set(operatingDates.filter((date) => date >= from && date <= to))
  const txByDate = new Map<string, Transaction[]>()
  for (const tx of transactions) {
    const date = transactionBusinessDate(tx)
    if (date < from || date > to) continue
    dateSet.add(date)
    const list = txByDate.get(date) ?? []
    list.push(tx)
    txByDate.set(date, list)
  }

  // Build a quick set of completed transaction ids for COGS lookup
  const completedTxIds = new Set(
    transactions.filter((tx) => tx.status === 'completed').map((tx) => tx.id),
  )
  const snapshottedTxIds = new Set(transactions.filter((tx) => tx.cogs !== undefined).map((tx) => tx.id))

  // Group transaction items by date using the parent transaction's date
  const txDateMap = new Map<string, string>()
  for (const tx of transactions) {
    txDateMap.set(tx.id, transactionBusinessDate(tx))
  }

  // Group waste inventory entries by date
  const wasteByDate = new Map<string, InventoryEntry[]>()
  for (const entry of inventory) {
    if (entry.movement_type !== 'void_waste' && entry.movement_type !== 'adjustment') continue
    if (Number.isFinite(resetTime) && Date.parse(entry.occurred_at) <= resetTime) continue
    // Resetting balances is an inventory correction, not physical waste.
    if (entry.movement_type === 'adjustment' && entry.reason === 'System administrator stock reset') continue
    // For adjustments, only count negative (removal) entries as waste
    if (entry.movement_type === 'adjustment' && entry.quantity_delta >= 0) continue
    const date = entry.business_date ?? getBusinessDateKey(entry.occurred_at)
    if (date < from || date > to) continue
    dateSet.add(date)
    const list = wasteByDate.get(date) ?? []
    list.push(entry)
    wasteByDate.set(date, list)
  }

  // Sort dates ascending
  const dates = Array.from(dateSet).sort()

  return dates.map((date) => {
    const dayTx = txByDate.get(date) ?? []
    const completed = dayTx.filter((tx) => tx.status === 'completed')
    const voided = dayTx.filter((tx) => tx.status === 'voided')
    const revenue = completed.reduce((sum, tx) => sum + tx.total_amount, 0)

    // COGS: cost of items sold in completed transactions for this date
    let cogs = completed.reduce((sum, tx) => sum + (tx.cogs ?? 0), 0)
    for (const item of transactionItems) {
      if (!completedTxIds.has(item.transaction_id)) continue
      if (snapshottedTxIds.has(item.transaction_id)) continue
      const itemDate = txDateMap.get(item.transaction_id)
      if (itemDate !== date) continue
      const unitCost = item.product_id ? (costMap[item.product_id] ?? 0) : 0
      cogs += unitCost * item.quantity
    }

    // Waste cost: cost of wasted items (void_waste entries + negative adjustments)
    const wasteEntries = wasteByDate.get(date) ?? []
    const countedWasteProducts = new Set<string>()
    let wasteCost = 0
    for (const entry of wasteEntries) {
      const unitCost = costMap[entry.product_id] ?? 0
      if (entry.movement_type === 'void_waste') {
        // A reversal creates one zero-delta marker per sale item. Match the
        // transaction and product, counting repeated product lines only once.
        const wasteKey = entry.reference_id ? `${entry.reference_id}:${entry.product_id}` : entry.id
        if (countedWasteProducts.has(wasteKey)) continue
        countedWasteProducts.add(wasteKey)
        const refItems = transactionItems.filter(
          (item) => item.transaction_id === entry.reference_id && item.product_id === entry.product_id,
        )
        if (refItems.length > 0) {
          for (const item of refItems) {
            wasteCost += unitCost * item.quantity
          }
        } else {
          // Older/imported waste rows may carry a quantity. Zero-delta markers
          // without their sale items do not tell us how many units were wasted.
          wasteCost += Math.abs(entry.quantity_delta) * unitCost
        }
      } else {
        // Adjustment deltas are in base units, not purchased packs.
        wasteCost += Math.abs(entry.quantity_delta) * (entry.unit_cost ?? baseUnitCostMap[entry.product_id] ?? 0)
      }
    }

    const legacyClosing = closings.find((closing) => closing.business_date === date)
    const fixedOverhead = legacyClosing?.overhead_cost ?? dailyOverhead
    const recordedDeductions = deductions.filter((entry) => entry.business_date === date &&
      (!Number.isFinite(resetTime) || Date.parse(entry.occurred_at) > resetTime))
    const revenueDeduction = recordedDeductions.length > 0
      ? recordedDeductions.reduce((sum, entry) => sum + entry.amount, 0)
      : (legacyClosing?.revenue_deduction ?? 0)
    const profitDeduction = recordedDeductions.length > 0
      ? recordedDeductions.filter((entry) => entry.affects_profit).reduce((sum, entry) => sum + entry.amount, 0)
      : revenueDeduction
    const netProfit = revenue - cogs - wasteCost - fixedOverhead - profitDeduction

    return {
      businessDate: date,
      revenue,
      cogs,
      wasteCost,
      fixedOverhead,
      revenueDeduction,
      profitDeduction,
      netProfit,
      completedSales: completed.length,
      voidedSales: voided.length,
    }
  })
}
