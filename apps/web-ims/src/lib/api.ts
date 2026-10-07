import type { SupabaseClient } from '@supabase/supabase-js'
import { DEFAULT_OVERHEAD_ITEMS, getBusinessDateKey } from './dashboard.ts'
import type {
  Category,
  BusinessDay,
  RevenueDeduction,
  SaleReversal,
  ClosedDayCorrection,
  InventoryEntry,
  OverheadItem,
  Product,
  ManagedUser,
  Stall,
  Transaction,
  TransactionItem,
  SaleComponent,
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

function newestFirst<T extends { id: string }>(rows: T[], field: keyof T): T[] {
  return rows.sort((left, right) => String(right[field] ?? '').localeCompare(String(left[field] ?? '')) ||
    right.id.localeCompare(left.id))
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
  const snapshot = throwIfError(await client.rpc('get_ims_financial_snapshot', { p_stall_id: stallId })) as {
    stall: Stall | null; categories: Category[]; products: Product[]; recipes: ProductRecipe[];
    inventory: InventoryEntry[]; transactions: Transaction[]; business_days: BusinessDay[];
    transaction_items: TransactionItem[]; sale_components: SaleComponent[];
    daily_store_closings: DailyStoreClosing[]; revenue_deductions: RevenueDeduction[];
    sale_reversals: SaleReversal[];
    closed_day_corrections: ClosedDayCorrection[];
  }
  const stallResult = { data: snapshot.stall, error: null }
  const categoryResult = { data: snapshot.categories, error: null }
  const productResult = { data: snapshot.products, error: null }
  const recipeResult = { data: snapshot.recipes, error: null }
  const inventoryResult = { data: snapshot.inventory, error: null }
  const transactionResult = { data: snapshot.transactions, error: null }
  const businessDayResult = { data: snapshot.business_days, error: null }
  const initialClosingResult = { data: snapshot.daily_store_closings, error: null }
  const initialDeductionResult = { data: snapshot.revenue_deductions, error: null }
  const reversalResult = { data: snapshot.sale_reversals, error: null }
  const closingResult = initialClosingResult
  const deductionResult = initialDeductionResult
  const items = snapshot.transaction_items.map((item) => ({
    ...item, quantity: Number(item.quantity), unit_price: Number(item.unit_price), line_total: Number(item.line_total),
  }))
  const saleComponents = snapshot.sale_components.map((component) => ({
    ...component, quantity: Number(component.quantity), cost_total: Number(component.cost_total),
  }))

  const recipesUnavailable = false
  const closingsUnavailable = false
  const deductionsUnavailable = false

  const rawStall = throwIfError(stallResult) as Stall
  const businessDateById = new Map(throwIfError(businessDayResult).map((day) => [day.id, day.business_date]))
  const businessDateFor = (dayId: string | null | undefined, occurredAt: string) => {
    if (!dayId) return getBusinessDateKey(occurredAt)
    const date = businessDateById.get(dayId)
    if (!date) throw new Error(`The operating day for record ${dayId} is unavailable. Refresh or contact support.`)
    return date
  }
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
    inventory: newestFirst(throwIfError(inventoryResult).map((entry) => ({
      ...entry,
      quantity_delta: Number(entry.quantity_delta),
      unit_cost: entry.unit_cost == null ? null : Number(entry.unit_cost),
      business_date: businessDateFor(entry.business_day_id, entry.occurred_at),
    })) as InventoryEntry[], 'occurred_at'),
    transactions: newestFirst(throwIfError(transactionResult).map((transaction) => ({
      ...transaction,
      subtotal: Number(transaction.subtotal),
      total_amount: Number(transaction.total_amount),
      cash_received: transaction.cash_received === null ? null : Number(transaction.cash_received),
      change_amount: transaction.change_amount === null ? null : Number(transaction.change_amount),
      cogs: transaction.cogs == null ? undefined : Number(transaction.cogs),
      business_date: businessDateFor(transaction.business_day_id, transaction.occurred_at),
    })) as Transaction[], 'occurred_at'),
    transactionItems: items,
    saleComponents,
    businessDays: newestFirst(throwIfError(businessDayResult).map((day) => ({
      ...day,
      closing_cash_total: day.closing_cash_total === null ? null : Number(day.closing_cash_total),
    })) as BusinessDay[], 'opened_at'),
    revenueDeductions: newestFirst((deductionsUnavailable ? [] : throwIfError(deductionResult)).map((deduction) => ({
      ...deduction, amount: Number(deduction.amount), business_date: businessDateById.get(deduction.business_day_id) ?? getBusinessDateKey(deduction.occurred_at),
    })) as RevenueDeduction[], 'occurred_at'),
    saleReversals: newestFirst(throwIfError(reversalResult).map((reversal) => ({
      ...reversal,
      cash_returned: Number(reversal.cash_returned),
      original_business_date: businessDateFor(reversal.original_day_id, reversal.occurred_at),
      payout_business_date: businessDateFor(reversal.payout_day_id, reversal.occurred_at),
    })) as SaleReversal[], 'occurred_at'),
    closedDayCorrections: snapshot.closed_day_corrections.map((correction) => ({
      ...correction,
      added_gross: Number(correction.added_gross), added_cogs: Number(correction.added_cogs),
      business_date: businessDateFor(correction.business_day_id, correction.posted_at),
    })).sort((left, right) => right.posted_at.localeCompare(left.posted_at)),
    deductionsAvailable: !deductionsUnavailable,
    recipes: (recipesUnavailable ? [] : throwIfError(recipeResult)).map((recipe) => ({ ...recipe, quantity: Number(recipe.quantity) })) as ProductRecipe[],
    dailyClosings: newestFirst((closingsUnavailable ? [] : throwIfError(closingResult)).map((closing) => ({
      ...closing,
      gross_sales: Number(closing.gross_sales), cogs: Number(closing.cogs), waste_cost: Number(closing.waste_cost),
      overhead_cost: Number(closing.overhead_cost), revenue_deduction: Number(closing.revenue_deduction ?? 0), net_profit: Number(closing.net_profit),
      expected_cash: Number(closing.expected_cash), collected_cash: Number(closing.collected_cash),
    })) as DailyStoreClosing[], 'business_date'),
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
