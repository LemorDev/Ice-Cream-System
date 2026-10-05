import type { Product } from './types'

/** Inventory ledger quantities are always stored in the product's base unit. */
export function receivedBaseUnits(packCount: number, product: Pick<Product, 'pack_size' | 'conversion_rate'>): number {
  const unitsPerPack = product.pack_size * product.conversion_rate
  const baseUnits = packCount * unitsPerPack
  if (!Number.isFinite(packCount) || packCount <= 0 || !Number.isFinite(unitsPerPack) || unitsPerPack <= 0 ||
      !Number.isFinite(baseUnits) || baseUnits > 999_999_999.999) {
    throw new Error('Enter a valid pack count and check the product pack size and conversion rate.')
  }
  const rounded = Math.round(baseUnits * 1000) / 1000
  if (rounded <= 0 || Math.abs(rounded - baseUnits) > 1e-8) {
    throw new Error('Received quantity must convert to at least 0.001 base units without losing precision.')
  }
  return rounded
}

export function targetBaseUnits(quantity: number, unit: 'packs' | 'base', product: Pick<Product, 'pack_size' | 'conversion_rate'>): number {
  if (!Number.isFinite(quantity) || quantity < 0) throw new Error('Enter a valid non-negative stock count.')
  if (quantity === 0) return 0
  if (unit === 'packs') return receivedBaseUnits(quantity, product)
  const rounded = Math.round(quantity * 1000) / 1000
  if (rounded > 999_999_999.999 || Math.abs(rounded - quantity) > 1e-8) {
    throw new Error('Stock counts must use at most three decimal places.')
  }
  return rounded
}
