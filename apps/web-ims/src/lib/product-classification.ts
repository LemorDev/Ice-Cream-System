import type { Product } from './types'

export type ProductTypeFilter = 'all' | Product['product_type']

export function isStockable(product: Pick<Product, 'product_type'>): boolean {
  return product.product_type === 'raw' || product.product_type === 'packaging'
}

export function productTypeLabel(type: Product['product_type']): string {
  switch (type) {
    case 'sellable': return 'Sellable menu item'
    case 'raw': return 'Raw ingredient'
    case 'packaging': return 'Packaging'
  }
}

export function productMatchesType(product: Pick<Product, 'product_type'>, filter: ProductTypeFilter): boolean {
  return filter === 'all' || product.product_type === filter
}
