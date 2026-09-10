import assert from 'node:assert/strict'
import test from 'node:test'
import { getDailyReconciliation, recipeCost, unitCost } from './recipes.ts'
import type { InventoryEntry, Product, ProductRecipe, Transaction, TransactionItem } from './types.ts'

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

test('daily reconciliation derives recipe usage and expected closing stock', () => {
  const inventory = [{ product_id: 'powder', quantity_delta: 2000, movement_type: 'receive', occurred_at: '2026-09-10T08:00:00.000' }] as InventoryEntry[]
  const transactions = [{ id: 'tx', status: 'completed', occurred_at: '2026-09-10T10:00:00.000' }] as Transaction[]
  const items = [{ transaction_id: 'tx', product_id: 'serve', quantity: 3 }] as TransactionItem[]
  const [row] = getDailyReconciliation('2026-09-10', [powder], recipe, inventory, transactions, items)
  assert.equal(row.starting, 0)
  assert.equal(row.salesUsage, 240)
  assert.equal(row.expected, 1760)
})
