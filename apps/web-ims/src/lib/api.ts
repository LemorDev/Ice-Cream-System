import type { SupabaseClient } from '@supabase/supabase-js'
import type {
  Category,
  InventoryEntry,
  Product,
  Stall,
  Transaction,
  TransactionItem,
  WorkspaceData,
} from './types'

export type DbClient = SupabaseClient

function throwIfError<T>(result: { data: T | null; error: { message: string } | null }): T {
  if (result.error) throw new Error(result.error.message)
  if (result.data === null) throw new Error('The database returned no data.')
  return result.data
}

export async function loadWorkspace(client: DbClient): Promise<WorkspaceData> {
  const [stallResult, categoryResult, productResult, inventoryResult, transactionResult, itemResult] = await Promise.all([
    client.from('stalls').select('id, name, code, updated_at').is('deleted_at', null).order('name').limit(1),
    client.from('product_categories').select('id, name, sort_order').is('deleted_at', null).order('sort_order').order('name'),
    client.from('products').select('id, stall_id, category_id, sku, name, unit, sale_price, cost_price, low_stock_threshold, pack_size, conversion_rate, is_sellable, updated_at, deleted_at').is('deleted_at', null).order('name'),
    client.from('inventory_ledger').select('id, product_id, quantity_delta, movement_type, reason, occurred_at').is('deleted_at', null).order('occurred_at', { ascending: false }).limit(5000),
    client.from('transactions').select('id, receipt_number, status, subtotal, total_amount, cash_received, change_amount, occurred_at').is('deleted_at', null).order('occurred_at', { ascending: false }).limit(1000),
    client.from('transaction_items').select('id, transaction_id, product_id, product_name, quantity, unit_price, line_total').is('deleted_at', null).limit(5000),
  ])

  return {
    stall: throwIfError(stallResult)[0] as Stall | undefined ?? null,
    categories: throwIfError(categoryResult) as Category[],
    products: throwIfError(productResult).map((product) => ({
      ...product,
      sale_price: Number(product.sale_price),
      cost_price: Number(product.cost_price),
      low_stock_threshold: Number(product.low_stock_threshold),
      pack_size: Number(product.pack_size ?? 1),
      conversion_rate: Number(product.conversion_rate ?? 1),
    })) as Product[],
    inventory: throwIfError(inventoryResult).map((entry) => ({
      ...entry,
      quantity_delta: Number(entry.quantity_delta),
    })) as InventoryEntry[],
    transactions: throwIfError(transactionResult).map((transaction) => ({
      ...transaction,
      subtotal: Number(transaction.subtotal),
      total_amount: Number(transaction.total_amount),
      cash_received: transaction.cash_received === null ? null : Number(transaction.cash_received),
      change_amount: transaction.change_amount === null ? null : Number(transaction.change_amount),
    })) as Transaction[],
    transactionItems: throwIfError(itemResult).map((item) => ({
      ...item,
      quantity: Number(item.quantity),
      unit_price: Number(item.unit_price),
      line_total: Number(item.line_total),
    })) as TransactionItem[],
  }
}

export function getStockByProduct(inventory: InventoryEntry[]) {
  return inventory.reduce<Record<string, number>>((stock, entry) => {
    stock[entry.product_id] = (stock[entry.product_id] ?? 0) + entry.quantity_delta
    return stock
  }, {})
}

export async function updateStall(client: DbClient, stallId: string, values: Pick<Stall, 'name' | 'code'>) {
  return throwIfError(await client.from('stalls').update(values).eq('id', stallId).select('id, name, code, updated_at').single()) as Stall
}

export async function createCategory(client: DbClient, values: Pick<Category, 'name' | 'sort_order'> & { stall_id: string }) {
  return throwIfError(await client.from('product_categories').insert(values).select('id, name, sort_order').single()) as Category
}

export async function saveProduct(client: DbClient, values: Omit<Product, 'id' | 'updated_at' | 'deleted_at' | 'sku'> & { sku?: string }, productId?: string) {
  const query = productId
    ? client.from('products').update(values).eq('id', productId).select().single()
    : client.from('products').insert(values).select().single()
  return throwIfError(await query) as Product
}

export async function archiveProduct(client: DbClient, productId: string) {
  throwIfError(await client.from('products').update({ deleted_at: new Date().toISOString(), is_sellable: false }).eq('id', productId))
}

export async function addInventoryEntry(client: DbClient, values: Omit<InventoryEntry, 'id' | 'occurred_at'> & { stall_id: string }) {
  return throwIfError(await client.from('inventory_ledger').insert(values).select().single()) as InventoryEntry
}

export async function reverseTransaction(client: DbClient, transactionId: string, reason: string, restock: boolean) {
  return throwIfError(await client.rpc('reverse_sale_inventory_ledger', {
    p_transaction_id: transactionId,
    p_reason: reason,
    p_restock: restock,
  })) as number
}
