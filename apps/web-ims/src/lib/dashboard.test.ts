import assert from 'node:assert/strict'
import test from 'node:test'
import { formatDateRangeLabel, getBusinessDateKey, getDailyProfitReport, getDashboardMetrics, getProductPerformance, getRevenueTrend } from './dashboard.ts'
import type { InventoryEntry, Product, Transaction, TransactionItem } from './types'

const products = [
  { id: 'vanilla', is_sellable: true, low_stock_threshold: 2 },
  { id: 'cone', is_sellable: false, low_stock_threshold: 0 },
] as Product[]

const inventory = [
  { product_id: 'vanilla', quantity_delta: 5 },
  { product_id: 'vanilla', quantity_delta: -4 },
  { product_id: 'cone', quantity_delta: 10 },
] as InventoryEntry[]

const transactions = [
  { id: 'sale-1', status: 'completed', total_amount: 40, occurred_at: '2026-08-04T10:00:00Z' },
  { id: 'void-1', status: 'voided', total_amount: 20, occurred_at: '2026-08-04T11:00:00Z' },
  { id: 'old-1', status: 'completed', total_amount: 99, occurred_at: '2026-08-03T11:00:00Z' },
] as Transaction[]

test('dashboard sales only count completed transactions for the selected day', () => {
  const result = getDashboardMetrics(products, inventory, transactions, '2026-08-04')
  assert.equal(result.sales, 40)
  assert.equal(result.completedSales, 1)
})

test('dashboard stock sums ledger movements and flags threshold breaches', () => {
  const result = getDashboardMetrics(products, inventory, transactions, '2026-08-04')
  assert.equal(result.stock.vanilla, 1)
  assert.deepEqual(result.lowStock, ['vanilla'])
  assert.equal(result.activeProducts, 1)
})

test('business reporting uses the Manila calendar date', () => {
  assert.equal(getBusinessDateKey('2026-08-04T23:30:00Z'), '2026-08-05')
})

test('revenue trend includes zero-sales days and completed transactions only', () => {
  const result = getRevenueTrend(transactions, '2026-08-03', '2026-08-05')
  assert.deepEqual(result, [
    { date: '2026-08-03', revenue: 99, orders: 1 },
    { date: '2026-08-04', revenue: 40, orders: 1 },
    { date: '2026-08-05', revenue: 0, orders: 0 },
  ])
})

test('report date label follows the selected range', () => {
  assert.equal(formatDateRangeLabel('2026-09-01', '2026-09-10'), 'Sep 1, 2026 – Sep 10, 2026')
  assert.equal(formatDateRangeLabel('2026-09-10', '2026-09-10'), 'Sep 10, 2026')
  assert.equal(formatDateRangeLabel('', '2026-09-10'), 'Selected period')
})

test('product performance ranks completed-sale revenue and excludes voided sales', () => {
  const items = [
    { transaction_id: 'sale-1', product_id: 'vanilla', product_name: 'Vanilla', quantity: 2, line_total: 80 },
    { transaction_id: 'sale-1', product_id: 'cone', product_name: 'Cone', quantity: 1, line_total: 20 },
    { transaction_id: 'void-1', product_id: 'vanilla', product_name: 'Vanilla', quantity: 4, line_total: 160 },
  ] as TransactionItem[]
  assert.deepEqual(getProductPerformance(transactions, items, '2026-08-04', '2026-08-04'), [
    { name: 'Vanilla', unitsSold: 2, revenue: 80, orders: 1 },
    { name: 'Cone', unitsSold: 1, revenue: 20, orders: 1 },
  ])
})

test('fixed overhead breakdown sums to 743.33 exactly', async () => {
  const { FIXED_OVERHEAD_ITEMS, DAILY_FIXED_OVERHEAD } = await import('./dashboard.ts')
  assert.equal(FIXED_OVERHEAD_ITEMS.length, 4)
  const sum = FIXED_OVERHEAD_ITEMS.reduce((acc, item) => acc + item.dailyRate, 0)
  assert.equal(Number(sum.toFixed(2)), 743.33)
  assert.equal(Number(DAILY_FIXED_OVERHEAD.toFixed(2)), 743.33)
})

const costProducts = [
  { id: 'vanilla', cost_price: 10 },
  { id: 'cone', cost_price: 2 },
] as Product[]

function wasteMarker(id: string, productId: string): InventoryEntry {
  return {
    id,
    product_id: productId,
    quantity_delta: 0,
    movement_type: 'void_waste',
    reason: 'Quality issue',
    reference_id: 'void-1',
    occurred_at: '2026-08-04T11:00:00Z',
  }
}

test('waste uses the referenced sale quantity instead of assuming one unit', () => {
  const items = [
    { transaction_id: 'void-1', product_id: 'vanilla', quantity: 5 },
    { transaction_id: 'sale-1', product_id: 'vanilla', quantity: 2 },
  ] as TransactionItem[]
  const [report] = getDailyProfitReport(
    costProducts, [wasteMarker('waste-1', 'vanilla')], transactions, items,
    '2026-08-04', '2026-08-04', [],
  )

  assert.equal(report.wasteCost, 50)
  assert.equal(report.cogs, 20)
  assert.equal(report.netProfit, -30)
})

test('waste counts each product quantity once across multi-product and repeated product lines', () => {
  const items = [
    { transaction_id: 'void-1', product_id: 'vanilla', quantity: 3 },
    { transaction_id: 'void-1', product_id: 'vanilla', quantity: 2 },
    { transaction_id: 'void-1', product_id: 'cone', quantity: 5 },
  ] as TransactionItem[]
  const markers = [
    wasteMarker('waste-1', 'vanilla'),
    wasteMarker('waste-2', 'vanilla'),
    wasteMarker('waste-3', 'cone'),
  ]
  const [report] = getDailyProfitReport(
    costProducts, markers, transactions, items, '2026-08-04', '2026-08-04', [],
  )

  assert.equal(report.wasteCost, 60)
  assert.equal(report.cogs, 0)
})

test('stock adjustments count removals as waste and exclude restocked products', () => {
  const inventory = [
    { ...wasteMarker('remove-1', 'vanilla'), movement_type: 'adjustment', quantity_delta: -3 },
    { ...wasteMarker('add-1', 'vanilla'), movement_type: 'adjustment', quantity_delta: 5 },
    { ...wasteMarker('restock-1', 'cone'), movement_type: 'void_restock', quantity_delta: 5 },
  ] as InventoryEntry[]
  const [report] = getDailyProfitReport(
    costProducts, inventory, transactions, [], '2026-08-04', '2026-08-04', [],
  )

  assert.equal(report.wasteCost, 30)
})

test('profit report includes an opened business day even when it has no sales', () => {
  const [report] = getDailyProfitReport(
    costProducts, [], [], [], '2026-08-05', '2026-08-05', [{ key: 'rent', label: 'Rent', dailyRate: 200 }], ['2026-08-05'],
  )

  assert.equal(report.businessDate, '2026-08-05')
  assert.equal(report.revenue, 0)
  assert.equal(report.fixedOverhead, 200)
  assert.equal(report.netProfit, -200)
})
