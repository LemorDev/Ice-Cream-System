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
  sku: string
  name: string
  unit: string
  sale_price: number
  cost_price: number
  low_stock_threshold: number
  pack_size: number
  conversion_rate: number
  is_sellable: boolean
  updated_at: string
  deleted_at: string | null
}

export type InventoryEntry = {
  id: string
  product_id: string
  quantity_delta: number
  movement_type: 'receive' | 'sale' | 'void_restock' | 'void_waste' | 'adjustment' | 'opening_balance'
  reason: string | null
  reference_id: string | null
  occurred_at: string
}

export type Transaction = {
  id: string
  receipt_number: string
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

export type DailyClosure = {
  id: string
  stall_id: string
  closed_by: string | null
  business_date: string
  cash_total: number
  notes: string | null
  updated_at: string
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
  dailyClosures: DailyClosure[]
  businessDays: BusinessDay[]
}

export type StockMap = Record<string, number>
