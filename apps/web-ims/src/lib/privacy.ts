const peso = new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' })

export function formatFinancialAmount(value: number, visible: boolean): string {
  return visible ? peso.format(value) : '₱••••'
}
