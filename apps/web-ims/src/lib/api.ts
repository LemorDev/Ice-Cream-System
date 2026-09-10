import type { SupabaseClient } from '@supabase/supabase-js'
import { DEFAULT_OVERHEAD_ITEMS } from './dashboard.ts'
import type {
  Category,
  BusinessDay,
  InventoryEntry,
  OverheadItem,
  Product,
  ManagedUser,
  Stall,
  Transaction,
  TransactionItem,
  ProductRecipe,
  DailyStoreClosing,
  WorkspaceData,
} from './types'

export type DbClient = SupabaseClient

function throwIfError<T>(result: { data: T | null; error: { message: string } | null }): T {
  if (result.error) throw new Error(result.error.message)
  if (result.data === null) throw new Error('The database returned no data.')
  return result.data
}

export function getOverheadForStall(stall: Stall | null): OverheadItem[] {
  if (!stall) return DEFAULT_OVERHEAD_ITEMS
  if (Array.isArray(stall.overhead_config) && stall.overhead_config.length > 0) {
    return stall.overhead_config
  }
  return DEFAULT_OVERHEAD_ITEMS
}

export async function loadWorkspace(client: DbClient, stallId: string): Promise<WorkspaceData> {
  const results = await Promise.all([
    client.from('stalls').select('*').eq('id', stallId).is('deleted_at', null).single(),
    client.from('product_categories').select('id, name, sort_order').eq('stall_id', stallId).is('deleted_at', null).order('sort_order').order('name'),
    client.from('products').select('id, stall_id, category_id, sku, name, unit, sale_price, cost_price, low_stock_threshold, pack_size, conversion_rate, is_sellable, product_type, base_unit, updated_at, deleted_at').eq('stall_id', stallId).is('deleted_at', null).order('name'),
    client.from('inventory_ledger').select('id, product_id, quantity_delta, movement_type, reason, reference_id, occurred_at').eq('stall_id', stallId).is('deleted_at', null).order('occurred_at', { ascending: false }).limit(5000),
    client.from('transactions').select('id, receipt_number, status, subtotal, total_amount, cash_received, change_amount, occurred_at').eq('stall_id', stallId).is('deleted_at', null).order('occurred_at', { ascending: false }).limit(1000),
    client.from('transaction_items').select('id, transaction_id, product_id, product_name, quantity, unit_price, line_total').is('deleted_at', null).limit(5000),
    client.from('business_days').select('id, stall_id, device_id, cashier_id, business_date, opened_at, opening_notes, closed_at, closing_cash_total, closing_notes, updated_at').eq('stall_id', stallId).is('deleted_at', null).order('opened_at', { ascending: false }).limit(365),
    client.from('product_recipes').select('id, stall_id, parent_product_id, ingredient_product_id, quantity, updated_at').eq('stall_id', stallId).order('parent_product_id'),
    client.from('daily_store_closings').select('id, stall_id, business_day_id, business_date, gross_sales, cogs, waste_cost, overhead_cost, net_profit, expected_cash, collected_cash, device_id, closed_at').eq('stall_id', stallId).order('business_date', { ascending: false }).limit(365),
  ])
  const [stallResult, categoryResult, initialProductResult, inventoryResult, transactionResult, itemResult, businessDayResult, recipeResult, closingResult] = results
  let productResult = initialProductResult

  // Keep already-deployed projects usable while the recipe migration is being
  // rolled out. These fallbacks can be removed after every environment has
  // applied 202609110001_recipes_and_daily_closings.sql.
  if (productResult.error?.message.includes('product_type') || productResult.error?.message.includes('base_unit')) {
    productResult = await client.from('products').select('id, stall_id, category_id, sku, name, unit, sale_price, cost_price, low_stock_threshold, pack_size, conversion_rate, is_sellable, updated_at, deleted_at').eq('stall_id', stallId).is('deleted_at', null).order('name') as typeof productResult
  }
  const recipesUnavailable = Boolean(recipeResult.error && (
    recipeResult.error.message.includes('product_recipes') || recipeResult.error.message.includes('schema cache')
  ))
  const closingsUnavailable = Boolean(closingResult.error && (
    closingResult.error.message.includes('daily_store_closings') || closingResult.error.message.includes('schema cache')
  ))

  const rawStall = throwIfError(stallResult) as Stall
  const stall: Stall | null = rawStall
    ? {
        ...rawStall,
        overhead_config: getOverheadForStall(rawStall),
      }
    : null

  return {
    stall,
    categories: throwIfError(categoryResult) as Category[],
    products: throwIfError(productResult).map((product) => ({
      ...product,
      sale_price: Number(product.sale_price),
      cost_price: Number(product.cost_price),
      low_stock_threshold: Number(product.low_stock_threshold),
      pack_size: Number(product.pack_size ?? 1),
      conversion_rate: Number(product.conversion_rate ?? 1),
      product_type: product.product_type ?? (product.is_sellable ? 'sellable' : 'raw'),
      base_unit: product.base_unit ?? (product.unit === 'ml' ? 'ml' : product.unit === 'g' ? 'g' : 'piece'),
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
    businessDays: throwIfError(businessDayResult).map((day) => ({
      ...day,
      closing_cash_total: day.closing_cash_total === null ? null : Number(day.closing_cash_total),
    })) as BusinessDay[],
    recipes: (recipesUnavailable ? [] : throwIfError(recipeResult)).map((recipe) => ({ ...recipe, quantity: Number(recipe.quantity) })) as ProductRecipe[],
    dailyClosings: (closingsUnavailable ? [] : throwIfError(closingResult)).map((closing) => ({
      ...closing,
      gross_sales: Number(closing.gross_sales), cogs: Number(closing.cogs), waste_cost: Number(closing.waste_cost),
      overhead_cost: Number(closing.overhead_cost), net_profit: Number(closing.net_profit),
      expected_cash: Number(closing.expected_cash), collected_cash: Number(closing.collected_cash),
    })) as DailyStoreClosing[],
  }
}

export async function listAccessibleStalls(client: DbClient) {
  return throwIfError(await client.rpc('get_my_stalls')) as Stall[]
}

export async function listManagedUsers(client: DbClient, stallId: string) {
  return throwIfError(await client.rpc('list_managed_users', { p_stall_id: stallId })) as ManagedUser[]
}

export async function saveManagedUser(client: DbClient, values: {
  stallId: string; email: string; displayName: string; role: 'owner' | 'cashier'
  password?: string; userId?: string; isActive: boolean
}) {
  return throwIfError(await client.rpc('save_managed_user', {
    p_stall_id: values.stallId, p_email: values.email, p_display_name: values.displayName,
    p_role: values.role, p_password: values.password || null, p_user_id: values.userId || null,
    p_is_active: values.isActive,
  })) as ManagedUser
}

export async function setOwnerStalls(client: DbClient, ownerId: string, stallIds: string[]) {
  const { error } = await client.rpc('set_owner_stalls', { p_owner_id: ownerId, p_stall_ids: stallIds })
  if (error) throw new Error(error.message)
}

export async function createManagedStall(client: DbClient, name: string, code: string) {
  return throwIfError(await client.rpc('create_managed_stall', { p_name: name, p_code: code })) as string
}

export type DeviceActivation = { device_id: string; activation_code: string }
export async function createDeviceActivation(client: DbClient, stallId: string, deviceName: string) {
  return throwIfError(await client.rpc('create_device_activation', {
    p_stall_id: stallId, p_device_name: deviceName,
  })) as DeviceActivation
}

export function getStockByProduct(inventory: InventoryEntry[]) {
  return inventory.reduce<Record<string, number>>((stock, entry) => {
    stock[entry.product_id] = (stock[entry.product_id] ?? 0) + entry.quantity_delta
    return stock
  }, {})
}

export async function updateStall(
  client: DbClient,
  stallId: string,
  values: Partial<Pick<Stall, 'name' | 'code' | 'overhead_config'>>,
) {
  return throwIfError(await client.rpc('update_managed_stall', {
    p_stall_id: stallId,
    p_name: values.name ?? null,
    p_code: values.code ?? null,
    p_overhead_config: values.overhead_config ?? null,
  })) as Stall
}

export async function createCategory(client: DbClient, values: Pick<Category, 'name' | 'sort_order'> & { stall_id: string }) {
  return throwIfError(await client.from('product_categories').insert(values).select('id, name, sort_order').single()) as Category
}

export async function archiveCategory(client: DbClient, categoryId: string) {
  const { error } = await client.from('product_categories').update({ deleted_at: new Date().toISOString() }).eq('id', categoryId)
  if (error) throw new Error(error.message)
}

export async function saveProduct(client: DbClient, values: Omit<Product, 'id' | 'updated_at' | 'deleted_at' | 'sku'> & { sku?: string }, productId?: string) {
  const query = productId
    ? client.from('products').update(values).eq('id', productId).select().single()
    : client.from('products').insert(values).select().single()
  return throwIfError(await query) as Product
}

export async function saveProductWithRecipe(
  client: DbClient,
  values: Omit<Product, 'id' | 'updated_at' | 'deleted_at' | 'sku'> & { sku?: string },
  recipe: Array<Pick<ProductRecipe, 'ingredient_product_id' | 'quantity'>>,
  productId?: string,
) {
  return throwIfError(await client.rpc('save_product_with_recipe', {
    p_product: { ...values, id: productId ?? null },
    p_recipe: recipe,
  })) as Product
}

export async function archiveProduct(client: DbClient, productId: string) {
  const { error } = await client.from('products').update({ deleted_at: new Date().toISOString(), is_sellable: false }).eq('id', productId)
  if (error) throw new Error(error.message)
}

export async function addInventoryEntry(client: DbClient, values: Omit<InventoryEntry, 'id' | 'occurred_at' | 'reference_id'> & { stall_id: string }) {
  return throwIfError(await client.from('inventory_ledger').insert(values).select().single()) as InventoryEntry
}

export async function reverseTransaction(client: DbClient, transactionId: string, reason: string, restock: boolean) {
  return throwIfError(await client.rpc('reverse_sale_inventory_ledger', {
    p_transaction_id: transactionId,
    p_reason: reason,
    p_restock: restock,
  })) as number
}
