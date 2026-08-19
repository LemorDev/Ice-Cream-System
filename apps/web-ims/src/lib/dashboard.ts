import type { InventoryEntry, Product, Transaction } from './types'

export function getDashboardMetrics(products: Product[], inventory: InventoryEntry[], transactions: Transaction[], today: string) {
  const stock = inventory.reduce<Record<string, number>>((result, entry) => {
    result[entry.product_id] = (result[entry.product_id] ?? 0) + entry.quantity_delta
    return result
  }, {})
  const todayTransactions = transactions.filter((transaction) => transaction.occurred_at.startsWith(today))
  const completedSales = todayTransactions.filter((transaction) => transaction.status === 'completed')
  return {
    sales: completedSales.reduce((sum, transaction) => sum + transaction.total_amount, 0),
    completedSales: completedSales.length,
    lowStock: products.filter((product) => (stock[product.id] ?? 0) <= product.low_stock_threshold).map((product) => product.id),
    activeProducts: products.filter((product) => product.is_sellable).length,
    stock,
  }
}
