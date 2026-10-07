export type AppRole = 'system_admin' | 'owner' | 'cashier'

export type OverheadItem = {
  key: string
  label: string
  dailyRate: number
  icon?: string
  description?: string
}

export type Stall = {
  id: string
  name: string
  code: string
  updated_at: string
  overhead_config?: OverheadItem[] | null
  financial_report_reset_at?: string | null
}

export type Category = {
  id: string
  name: string
  sort_order: number
}

export type Product = {
  id: string
  stall_id: string
  category_id: string | null
  sell_category: string | null
  sku: string
  name: string
  unit: string
  sale_price: number
  cost_price: number
  low_stock_threshold: number
  pack_size: number
  conversion_rate: number
  is_sellable: boolean
  product_type: 'raw' | 'packaging' | 'sellable'
  base_unit: 'g' | 'ml' | 'piece'
  updated_at: string
  deleted_at: string | null
}

export type ProductRecipe = {
  id: string
  stall_id: string
  parent_product_id: string
  ingredient_product_id: string
  quantity: number
  updated_at: string
}

export type DailyStoreClosing = {
  id: string
  stall_id: string
  business_day_id: string | null
  business_date: string
  gross_sales: number
  cogs: number
  waste_cost: number
  overhead_cost: number
  revenue_deduction: number
  net_profit: number
  expected_cash: number
  collected_cash: number
  device_id: string | null
  closed_at: string
}

export type InventoryEntry = {
  id: string
  product_id: string
  business_day_id?: string | null
  business_date?: string
  unit_cost?: number | null
  quantity_delta: number
  movement_type: 'receive' | 'sale' | 'void_restock' | 'void_waste' | 'adjustment' | 'opening_balance'
  reason: string | null
  reference_id: string | null
  occurred_at: string
}

export type Transaction = {
  id: string
  receipt_number: string
  business_day_id?: string | null
  business_date?: string
  cogs?: number
  status: 'completed' | 'voided' | 'refunded'
  subtotal: number
  total_amount: number
  cash_received: number | null
  change_amount: number | null
  occurred_at: string
}

export type TransactionItem = {
  id: string
  transaction_id: string
  product_id: string | null
  product_name: string
  quantity: number
  unit_price: number
  line_total: number
}

export type BusinessDay = {
  id: string
  stall_id: string
  device_id: string
  cashier_id: string
  business_date: string
  opened_at: string
  opening_notes: string | null
  closed_at: string | null
  closing_cash_total: number | null
  closing_notes: string | null
  updated_at: string
}

export type RevenueDeduction = {
  id: string
  stall_id: string
  business_day_id: string
  business_date: string
  amount: number
  affects_profit: boolean
  reason: string
  cashier_id: string
  occurred_at: string
}

export type ManagedUser = {
  id: string
  stall_id: string
  email: string
  display_name: string
  role: 'owner' | 'cashier'
  is_active: boolean
  updated_at: string
  stall_ids: string[]
}

export type DailyProfitReport = {
  businessDate: string
  revenue: number
  cogs: number
  wasteCost: number
  fixedOverhead: number
  revenueDeduction: number
  profitDeduction: number
  netProfit: number
  completedSales: number
  voidedSales: number
}

export type WorkspaceData = {
  stall: Stall | null
  categories: Category[]
  products: Product[]
  inventory: InventoryEntry[]
  transactions: Transaction[]
  transactionItems: TransactionItem[]
  businessDays: BusinessDay[]
  revenueDeductions: RevenueDeduction[]
  deductionsAvailable: boolean
  recipes: ProductRecipe[]
  dailyClosings: DailyStoreClosing[]
}

export type StockMap = Record<string, number>
