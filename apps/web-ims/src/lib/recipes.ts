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
  powderOpened: number
  waste: number
  expected: number
  physicalCount: number | null
  countUnit: string
  variance: number | null
}

export function getDailyReconciliation(
  date: string,
  products: Product[], _recipes: ProductRecipe[], inventory: InventoryEntry[],
  _transactions: Transaction[], _items: TransactionItem[], operatingDay?: BusinessDay,
): ReconciliationRow[] {
  const beforeStart = Date.parse(operatingDay?.opened_at ?? `${date}T00:00:00.000Z`)
  const beforeEnd = operatingDay?.closed_at ? Date.parse(operatingDay.closed_at) : Infinity
  const belongsToDay = (entry: InventoryEntry) => operatingDay
    ? entry.business_day_id === operatingDay.id || (!entry.business_day_id &&
        Date.parse(entry.occurred_at) >= beforeStart && Date.parse(entry.occurred_at) <= beforeEnd)
    : (entry.business_date ?? entry.occurred_at.slice(0, 10)) === date
  return products.filter((product) => product.product_type !== 'sellable').map((product) => {
    const productEntries = inventory.filter((entry) => entry.product_id === product.id)
    const starting = productEntries.filter((entry) => Date.parse(entry.occurred_at) < beforeStart)
      .reduce((sum, entry) => sum + entry.quantity_delta, 0)
    const dayEntries = productEntries.filter(belongsToDay)
    const closingCount = dayEntries.find((entry) => entry.movement_type === 'closing_count')
    const movements = dayEntries.filter((entry) => entry.movement_type !== 'closing_count' &&
      (!closingCount || Date.parse(entry.occurred_at) <= Date.parse(closingCount.occurred_at)))
    const waste = Math.abs(movements.filter((entry) => entry.movement_type === 'adjustment' && entry.quantity_delta < 0).reduce((sum, entry) => sum + entry.quantity_delta, 0))
    const salesUsage = Math.abs(movements.filter((entry) => entry.movement_type === 'sale').reduce((sum, entry) => sum + entry.quantity_delta, 0))
    const powderOpened = movements.filter((entry) => entry.movement_type === 'pack_unsealed' && entry.quantity_delta < 0)
      .reduce((sum, entry) => sum - entry.quantity_delta, 0)
    const expected = starting + movements.reduce((sum, entry) => sum + entry.quantity_delta, 0)
    return {
      product, starting, salesUsage, powderOpened, waste, expected,
      physicalCount: closingCount?.counted_quantity ?? null,
      countUnit: closingCount?.counted_unit ?? product.base_unit,
      variance: closingCount?.counted_unit === 'pack'
        ? closingCount.sealed_packs_delta ?? 0
        : closingCount?.quantity_delta ?? null,
    }
  })
}
