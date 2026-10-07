import assert from 'node:assert/strict'
import test from 'node:test'
import { getDailyReconciliation, recipeCost, unitCost } from './recipes.ts'
import type { BusinessDay, InventoryEntry, Product, ProductRecipe, Transaction, TransactionItem } from './types.ts'

const powder = { id: 'powder', product_type: 'raw', cost_price: 500, pack_size: 1000, conversion_rate: 1 } as Product
const cone = { id: 'cone', product_type: 'packaging', cost_price: 100, pack_size: 50, conversion_rate: 1 } as Product
const recipe = [
  { parent_product_id: 'serve', ingredient_product_id: 'powder', quantity: 80 },
  { parent_product_id: 'serve', ingredient_product_id: 'cone', quantity: 1 },
] as ProductRecipe[]

test('unit and recipe costs use pack size and recipe quantity', () => {
  assert.equal(unitCost(powder), 0.5)
  assert.equal(recipeCost(recipe, [powder, cone], 'serve'), 42)
})

test('daily reconciliation uses posted sale ingredients and expected closing stock', () => {
  const inventory = [
    { product_id: 'powder', quantity_delta: 2000, movement_type: 'receive', occurred_at: '2026-09-10T08:00:00.000' },
    { product_id: 'powder', quantity_delta: -240, movement_type: 'sale', occurred_at: '2026-09-10T10:00:00.000' },
  ] as InventoryEntry[]
  const transactions = [{ id: 'tx', status: 'completed', occurred_at: '2026-09-10T10:00:00.000' }] as Transaction[]
  const items = [{ transaction_id: 'tx', product_id: 'serve', quantity: 3 }] as TransactionItem[]
  const [row] = getDailyReconciliation('2026-09-10', [powder], recipe, inventory, transactions, items)
  assert.equal(row.starting, 0)
  assert.equal(row.salesUsage, 240)
  assert.equal(row.expected, 1760)
})

test('overnight reconciliation keeps ingredient use on the opening day', () => {
  const day = {
    id: 'day-1', business_date: '2026-08-04', opened_at: '2026-08-04T15:55:00Z',
    closed_at: '2026-08-04T16:20:00Z',
  } as BusinessDay
  const movements = [
    { product_id: 'powder', quantity_delta: 200, movement_type: 'receive', occurred_at: '2026-08-04T15:50:00Z' },
    { product_id: 'powder', business_day_id: day.id, quantity_delta: -80, movement_type: 'sale', occurred_at: '2026-08-04T16:05:00Z' },
  ] as InventoryEntry[]

  const [row] = getDailyReconciliation('2026-08-04', [powder], recipe, movements, [], [], day)

  assert.equal(row.starting, 200)
  assert.equal(row.salesUsage, 80)
  assert.equal(row.expected, 120)
})
