import assert from 'node:assert/strict'
import test from 'node:test'
import { getDashboardMetrics } from './dashboard.ts'
import type { InventoryEntry, Product, Transaction } from './types'

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
