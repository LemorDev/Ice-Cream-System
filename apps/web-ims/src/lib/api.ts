import type { SupabaseClient } from '@supabase/supabase-js'
import { DEFAULT_OVERHEAD_ITEMS, getBusinessDateKey } from './dashboard.ts'
import type {
  Category,
  BusinessDay,
  RevenueDeduction,
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

type PageResult<T> = { data: T[] | null; error: { message: string } | null }
const PAGE_SIZE = 500

export async function fetchAllRows<T extends { id: string }>(
  page: (from: number, to: number) => PromiseLike<PageResult<T>>,
): Promise<PageResult<T>> {
  const rows: T[] = []
  const seen = new Set<string>()
  for (let from = 0; ;) {
    const result = await page(from, from + PAGE_SIZE - 1)
    if (result.error) return { data: null, error: result.error }
    const batch = result.data ?? []
    if (batch.length === 0) break
    for (const row of batch) {
      if (seen.has(row.id)) throw new Error('Workspace data changed during loading. Refresh to try again.')
      seen.add(row.id)
      rows.push(row)
    }
    // PostgREST may return fewer rows than requested when its server cap is
    // lower than PAGE_SIZE. Advance by rows received, then ask again.
    from += batch.length
  }
  return { data: rows, error: null }
}

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

export async function getTransactionReceiptItems(client: DbClient, transactionId: string): Promise<TransactionItem[]> {
  const result = await client.from('transaction_items')
    .select('id, transaction_id, product_id, product_name, quantity, unit_price, line_total')
    .eq('transaction_id', transactionId)
    .is('deleted_at', null)
    .order('id')
  return throwIfError(result).map((item) => ({
    ...item,
    quantity: Number(item.quantity),
    unit_price: Number(item.unit_price),
    line_total: Number(item.line_total),
  })) as TransactionItem[]
}

export async function loadWorkspace(client: DbClient, stallId: string): Promise<WorkspaceData> {
  const results = await Promise.all([
    client.from('stalls').select('*').eq('id', stallId).is('deleted_at', null).single(),
    fetchAllRows((from, to) => client.from('product_categories').select('id, name, sort_order').eq('stall_id', stallId).is('deleted_at', null).order('sort_order').order('id').range(from, to)),
    fetchAllRows((from, to) => client.from('products').select('id, stall_id, category_id, sell_category, sku, name, unit, sale_price, cost_price, low_stock_threshold, pack_size, conversion_rate, is_sellable, product_type, base_unit, updated_at, deleted_at').eq('stall_id', stallId).is('deleted_at', null).order('name').order('id').range(from, to)),
    fetchAllRows((from, to) => client.from('inventory_ledger').select('id, product_id, quantity_delta, movement_type, reason, reference_id, occurred_at').eq('stall_id', stallId).is('deleted_at', null).order('occurred_at', { ascending: false }).order('id', { ascending: false }).range(from, to)),
    fetchAllRows((from, to) => client.from('transactions').select('id, receipt_number, status, subtotal, total_amount, cash_received, change_amount, occurred_at').eq('stall_id', stallId).is('deleted_at', null).order('occurred_at', { ascending: false }).order('id', { ascending: false }).range(from, to)),
    fetchAllRows((from, to) => client.from('business_days').select('id, stall_id, device_id, cashier_id, business_date, opened_at, opening_notes, closed_at, closing_cash_total, closing_notes, updated_at').eq('stall_id', stallId).is('deleted_at', null).order('opened_at', { ascending: false }).order('id', { ascending: false }).range(from, to)),
    fetchAllRows((from, to) => client.from('product_recipes').select('id, stall_id, parent_product_id, ingredient_product_id, quantity, updated_at').eq('stall_id', stallId).order('parent_product_id').order('id').range(from, to)),
    fetchAllRows((from, to) => client.from('daily_store_closings').select('id, stall_id, business_day_id, business_date, gross_sales, cogs, waste_cost, overhead_cost, revenue_deduction, net_profit, expected_cash, collected_cash, device_id, closed_at').eq('stall_id', stallId).order('business_date', { ascending: false }).order('id', { ascending: false }).range(from, to)),
    fetchAllRows((from, to) => client.from('revenue_deductions').select('id, stall_id, business_day_id, amount, affects_profit, reason, cashier_id, occurred_at').eq('stall_id', stallId).order('occurred_at', { ascending: false }).order('id', { ascending: false }).range(from, to)),
  ])
  const [stallResult, categoryResult, initialProductResult, inventoryResult, transactionResult, businessDayResult, recipeResult, initialClosingResult, initialDeductionResult] = results
  let productResult = initialProductResult
  let closingResult = initialClosingResult
  const deductionResult = initialDeductionResult

  // Item rows have no stall_id. Fetch only items belonging to this stall's
  // complete sale list, in small groups that fit within PostgREST URL limits.
  const transactionIds = throwIfError(transactionResult).map((transaction) => transaction.id)
  const items: TransactionItem[] = []
  for (let index = 0; index < transactionIds.length; index += 50) {
    const ids = transactionIds.slice(index, index + 50)
    const result = await fetchAllRows((from, to) => client.from('transaction_items')
      .select('id, transaction_id, product_id, product_name, quantity, unit_price, line_total')
      .in('transaction_id', ids).is('deleted_at', null).order('id').range(from, to))
    items.push(...throwIfError(result).map((item) => ({
      ...item, quantity: Number(item.quantity), unit_price: Number(item.unit_price), line_total: Number(item.line_total),
    } as TransactionItem)))
  }

  // Keep deployed projects readable while recipe and POS category migrations
  // are rolled out. Writes with a POS category are guarded below.
  if (productResult.error?.message.includes('sell_category')) {
    productResult = await fetchAllRows((from, to) => client.from('products').select('id, stall_id, category_id, sku, name, unit, sale_price, cost_price, low_stock_threshold, pack_size, conversion_rate, is_sellable, product_type, base_unit, updated_at, deleted_at').eq('stall_id', stallId).is('deleted_at', null).order('name').order('id').range(from, to)) as typeof productResult
  }
  if (productResult.error?.message.includes('product_type') || productResult.error?.message.includes('base_unit')) {
    productResult = await fetchAllRows((from, to) => client.from('products').select('id, stall_id, category_id, sku, name, unit, sale_price, cost_price, low_stock_threshold, pack_size, conversion_rate, is_sellable, updated_at, deleted_at').eq('stall_id', stallId).is('deleted_at', null).order('name').order('id').range(from, to)) as typeof productResult
  }
  if (closingResult.error?.message.includes('revenue_deduction')) {
    closingResult = await fetchAllRows((from, to) => client.from('daily_store_closings').select('id, stall_id, business_day_id, business_date, gross_sales, cogs, waste_cost, overhead_cost, net_profit, expected_cash, collected_cash, device_id, closed_at').eq('stall_id', stallId).order('business_date', { ascending: false }).order('id', { ascending: false }).range(from, to)) as typeof closingResult
  }
  const recipesUnavailable = Boolean(recipeResult.error && (
    recipeResult.error.message.includes('product_recipes') || recipeResult.error.message.includes('schema cache')
  ))
  const closingsUnavailable = Boolean(closingResult.error && (
    closingResult.error.message.includes('daily_store_closings') || closingResult.error.message.includes('schema cache')
  ))
  const deductionsUnavailable = Boolean(deductionResult.error && (
    deductionResult.error.message.includes('revenue_deductions') || deductionResult.error.message.includes('affects_profit') || deductionResult.error.message.includes('schema cache')
  ))

  const rawStall = throwIfError(stallResult) as Stall
  const businessDateById = new Map(throwIfError(businessDayResult).map((day) => [day.id, day.business_date]))
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
      sell_category: product.sell_category ?? null,
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
    transactionItems: items,
    businessDays: throwIfError(businessDayResult).map((day) => ({
      ...day,
      closing_cash_total: day.closing_cash_total === null ? null : Number(day.closing_cash_total),
    })) as BusinessDay[],
    revenueDeductions: (deductionsUnavailable ? [] : throwIfError(deductionResult)).map((deduction) => ({
      ...deduction, amount: Number(deduction.amount), business_date: businessDateById.get(deduction.business_day_id) ?? getBusinessDateKey(deduction.occurred_at),
    })) as RevenueDeduction[],
    deductionsAvailable: !deductionsUnavailable,
    recipes: (recipesUnavailable ? [] : throwIfError(recipeResult)).map((recipe) => ({ ...recipe, quantity: Number(recipe.quantity) })) as ProductRecipe[],
    dailyClosings: (closingsUnavailable ? [] : throwIfError(closingResult)).map((closing) => ({
      ...closing,
      gross_sales: Number(closing.gross_sales), cogs: Number(closing.cogs), waste_cost: Number(closing.waste_cost),
      overhead_cost: Number(closing.overhead_cost), revenue_deduction: Number(closing.revenue_deduction ?? 0), net_profit: Number(closing.net_profit),
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

export type DeviceActivation = { device_id: string; activation_code: string; expires_at: string }
export type PosDeviceStatus = {
  active_device_id: string | null
  active_device_name: string | null
  transfer_ready_at: string | null
  pending_code_expires_at: string | null
  open_business_date: string | null
  recovery_incident_id: string | null
  recovery_status: 'pending' | 'activated' | 'reviewed' | null
  recovery_business_date: string | null
  recovery_known_sales: number | null
  recovery_known_deductions: number | null
}
export async function getPosDeviceStatus(client: DbClient, stallId: string) {
  return throwIfError(await client.rpc('get_pos_device_status', { p_stall_id: stallId })) as PosDeviceStatus
}
export async function revokePendingPosActivation(client: DbClient, stallId: string) {
  return throwIfError(await client.rpc('revoke_pending_pos_activation', { p_stall_id: stallId })) as number
}
export async function authorizePosRecovery(client: DbClient, stallId: string, reason: string) {
  return throwIfError(await client.rpc('authorize_pos_recovery', { p_stall_id: stallId, p_reason: reason })) as { status: 'ready'; device_id: string }
}
export async function reviewPosRecovery(client: DbClient, incidentId: string, notes: string) {
  return throwIfError(await client.rpc('review_pos_recovery', { p_incident_id: incidentId, p_notes: notes })) as { status: 'reviewed'; incident_id: string }
}
export async function adminCloseOpenBusinessDay(client: DbClient, businessDayId: string, collectedCash: number, reason: string) {
  return throwIfError(await client.rpc('admin_close_open_business_day', {
    p_business_day_id: businessDayId, p_collected_cash: collectedCash, p_reason: reason,
  })) as { status: 'closed'; business_day_id: string; closing_id: string }
}
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

export async function saveProduct(client: DbClient, values: Omit<Product, 'id' | 'updated_at' | 'deleted_at' | 'sku' | 'sell_category'> & { sku?: string; sell_category?: string | null }, productId?: string) {
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
  if (values.sell_category) {
    const { error } = await client.from('products').select('sell_category').eq('stall_id', values.stall_id).limit(1)
    if (error) throw new Error('POS categories are not ready in this IMS database. Apply the POS category migration before saving menu items.')
  }
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

export type SetStockCountResult = { status: 'adjusted' | 'unchanged'; previous_stock: number; target_stock: number; quantity_delta: number }
export async function setStockOnHand(client: DbClient, stallId: string, productId: string, targetStock: number, reason: string): Promise<SetStockCountResult> {
  return throwIfError(await client.rpc('set_stock_on_hand', {
    p_stall_id: stallId,
    p_product_id: productId,
    p_target_stock: targetStock,
    p_reason: reason,
  })) as SetStockCountResult
}

export async function reverseTransaction(client: DbClient, transactionId: string, reason: string, restock: boolean) {
  return throwIfError(await client.rpc('reverse_sale_inventory_ledger', {
    p_transaction_id: transactionId,
    p_reason: reason,
    p_restock: restock,
  })) as number
}

export type DataResetResult = {
  scope: 'operating_history' | 'stock'
  affected_records: number
  affected_operating_days?: number
}

export async function resetTransactionsAndRevenue(client: DbClient, stallId: string) {
  return throwIfError(await client.rpc('reset_stall_transactions', { p_stall_id: stallId })) as DataResetResult
}

export async function resetStockLevels(client: DbClient, stallId: string) {
  return throwIfError(await client.rpc('reset_stall_stock', { p_stall_id: stallId })) as DataResetResult
}
