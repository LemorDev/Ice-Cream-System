import assert from 'node:assert/strict'
import test from 'node:test'
import { getDailyProfitReport, getDashboardMetrics } from './dashboard.ts'
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
