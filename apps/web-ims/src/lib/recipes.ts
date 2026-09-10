import type { InventoryEntry, Product, ProductRecipe, Transaction, TransactionItem } from './types'

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
  products: Product[], recipes: ProductRecipe[], inventory: InventoryEntry[],
  transactions: Transaction[], items: TransactionItem[],
): ReconciliationRow[] {
  const beforeEnd = `${date}T23:59:59.999`
  const beforeStart = `${date}T00:00:00.000`
  const completed = new Set(transactions.filter((tx) => tx.status === 'completed' && tx.occurred_at >= beforeStart && tx.occurred_at <= beforeEnd).map((tx) => tx.id))
  const sold = new Map<string, number>()
  items.filter((item) => completed.has(item.transaction_id)).forEach((item) => {
    if (item.product_id) sold.set(item.product_id, (sold.get(item.product_id) ?? 0) + item.quantity)
  })
  return products.filter((product) => product.product_type !== 'sellable').map((product) => {
    const starting = inventory.filter((entry) => entry.product_id === product.id && entry.occurred_at < beforeStart).reduce((sum, entry) => sum + entry.quantity_delta, 0)
    const movements = inventory.filter((entry) => entry.product_id === product.id && entry.occurred_at >= beforeStart && entry.occurred_at <= beforeEnd)
    const receipts = movements.filter((entry) => entry.quantity_delta > 0).reduce((sum, entry) => sum + entry.quantity_delta, 0)
    const waste = Math.abs(movements.filter((entry) => entry.movement_type === 'void_waste' || (entry.movement_type === 'adjustment' && entry.quantity_delta < 0)).reduce((sum, entry) => sum + entry.quantity_delta, 0))
    const salesUsage = recipes.filter((recipe) => recipe.ingredient_product_id === product.id).reduce((sum, recipe) => sum + recipe.quantity * (sold.get(recipe.parent_product_id) ?? 0), 0)
    return { product, starting, salesUsage, waste, expected: starting + receipts - salesUsage - waste }
  })
}
