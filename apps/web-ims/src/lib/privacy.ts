const peso = new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' })

export function formatFinancialAmount(value: number, visible: boolean): string {
  return visible ? peso.format(value) : '₱••••'
}

export function formatUnitCostAmount(value: number, visible: boolean): string {
  if (!visible) return '₱••••'
  if (value === 0) return '₱0.00'
  const abs = Math.abs(value)
  const maxDigits = abs < 0.01 ? 4 : abs < 1 ? 3 : 2
  return new Intl.NumberFormat('en-PH', {
    style: 'currency',
    currency: 'PHP',
    minimumFractionDigits: 2,
    maximumFractionDigits: maxDigits,
  }).format(value)
}
