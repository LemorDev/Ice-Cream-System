export type AppRole = 'manager' | 'cashier'

export type Stall = {
  id: string
  name: string
  code: string
  updated_at: string
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

export type WorkspaceData = {
  stall: Stall | null
  categories: Category[]
  products: Product[]
  inventory: InventoryEntry[]
  transactions: Transaction[]
  transactionItems: TransactionItem[]
}

export type StockMap = Record<string, number>
