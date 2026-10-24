import type { BusinessDay, InventoryEntry, Product, ProductRecipe, Transaction, TransactionItem } from './types'

export function unitCost(product: Pick<Product, 'cost_price' | 'pack_size' | 'conversion_rate'>) {
  const usableUnits = product.pack_size * product.conversion_rate
  return usableUnits > 0 ? product.cost_price / usableUnits : 0
}

export function recipeCost(recipes: ProductRecipe[], products: Product[], parentId?: string) {
  return recipes
    .filter((recipe) => !parentId || recipe.parent_product_id === parentId)
    .reduce((sum, recipe) => {
      const ingredient = products.find((product) => product.id === recipe.ingredient_product_id)
      return sum + (ingredient ? unitCost(ingredient) * recipe.quantity : 0)
    }, 0)
}

export type ReconciliationRow = {
  product: Product
  starting: number
  salesUsage: number
  waste: number
  expected: number
}

export function getDailyReconciliation(
  date: string,
  products: Product[], _recipes: ProductRecipe[], inventory: InventoryEntry[],
  _transactions: Transaction[], _items: TransactionItem[], operatingDay?: BusinessDay,
): ReconciliationRow[] {
  const beforeStart = operatingDay?.opened_at ?? `${date}T00:00:00.000Z`
  const beforeEnd = operatingDay?.closed_at ?? '9999-12-31T23:59:59Z'
  const belongsToDay = (entry: InventoryEntry) => operatingDay
    ? entry.business_day_id === operatingDay.id || (!entry.business_day_id && entry.occurred_at >= beforeStart && entry.occurred_at <= beforeEnd)
    : (entry.business_date ?? entry.occurred_at.slice(0, 10)) === date
  return products.filter((product) => product.product_type !== 'sellable').map((product) => {
    const starting = inventory.filter((entry) => entry.product_id === product.id && entry.occurred_at < beforeStart).reduce((sum, entry) => sum + entry.quantity_delta, 0)
    const movements = inventory.filter((entry) => entry.product_id === product.id && belongsToDay(entry))
    const receipts = movements.filter((entry) => entry.quantity_delta > 0).reduce((sum, entry) => sum + entry.quantity_delta, 0)
    const waste = Math.abs(movements.filter((entry) => entry.movement_type === 'adjustment' && entry.quantity_delta < 0).reduce((sum, entry) => sum + entry.quantity_delta, 0))
    const salesUsage = Math.abs(movements.filter((entry) => entry.movement_type === 'sale').reduce((sum, entry) => sum + entry.quantity_delta, 0))
    return { product, starting, salesUsage, waste, expected: starting + receipts - salesUsage - waste }
  })
}
