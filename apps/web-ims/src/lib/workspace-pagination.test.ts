import assert from 'node:assert/strict'
import test from 'node:test'
import { loadWorkspace, type DbClient } from './api.ts'

type Row = Record<string, unknown> & { id: string }

function fakeClient(tables: Record<string, Row[]>): DbClient {
  return {
    async rpc(name: string, args: { p_stall_id: string }) {
      assert.equal(name, 'get_ims_financial_snapshot')
      const stallId = args.p_stall_id
      const scoped = (table: string) => (tables[table] ?? []).filter((row) => row.stall_id === stallId)
      const saleIds = new Set(scoped('transactions').map((sale) => sale.id))
      return { data: {
        stall: (tables.stalls ?? []).find((row) => row.id === stallId) ?? null, categories: scoped('product_categories'),
        products: scoped('products'), recipes: scoped('product_recipes'),
        inventory: scoped('inventory_ledger'), transactions: scoped('transactions'),
        business_days: scoped('business_days'), daily_store_closings: scoped('daily_store_closings'),
        revenue_deductions: scoped('revenue_deductions'), sale_reversals: scoped('sale_reversals'),
        closed_day_corrections: scoped('closed_day_corrections'),
        transaction_items: (tables.transaction_items ?? []).filter((row) => saleIds.has(String(row.transaction_id))),
        sale_components: (tables.sale_components ?? []).filter((row) => saleIds.has(String(row.transaction_id))),
      }, error: null }
    },
    from(table: string) {
      if (['stalls','product_categories','products','product_recipes',
        'inventory_ledger','transactions','business_days','daily_store_closings',
        'revenue_deductions','sale_reversals','transaction_items','sale_components'].includes(table)) {
        throw new Error(`Financial table ${table} must come from one snapshot`)
      }
      const filters: Array<(row: Row) => boolean> = []
      const sorting: Array<{ field: string; ascending: boolean }> = []
      const rows = () => [...(tables[table] ?? [])].filter((row) => filters.every((keep) => keep(row)))
        .sort((left, right) => {
          for (const { field, ascending } of sorting) {
            const comparison = String(left[field] ?? '').localeCompare(String(right[field] ?? ''))
            if (comparison !== 0) return ascending ? comparison : -comparison
          }
          return 0
        })
      const query = {
        select() { return query },
        eq(field: string, value: unknown) { filters.push((row) => row[field] === value); return query },
        is(field: string, value: unknown) { filters.push((row) => (row[field] ?? null) === value); return query },
        in(field: string, values: unknown[]) { filters.push((row) => values.includes(row[field])); return query },
        order(field: string, options?: { ascending?: boolean }) {
          sorting.push({ field, ascending: options?.ascending ?? true }); return query
        },
        // Emulate a server cap smaller than the client's requested page.
        async range(from: number, to: number) { return { data: rows().slice(from, Math.min(to + 1, from + 150)), error: null } },
        async single() { return { data: rows()[0] ?? null, error: null } },
      }
      return query
    },
  } as unknown as DbClient
}

test('one workspace snapshot loads 1201 sales and only their scoped children', async () => {
  const sales = Array.from({ length: 1201 }, (_, index) => ({
    id: `sale-${String(index).padStart(4, '0')}`, receipt_number: `R-${index}`,
    status: 'completed', subtotal: '10', total_amount: '10', cash_received: '10',
    change_amount: '0', occurred_at: '2026-10-06T00:00:00Z', stall_id: 'main',
  }))
  const items = sales.map((sale) => ({
    id: `item-${sale.id}`, transaction_id: sale.id, product_id: 'cone',
    product_name: 'Cone', quantity: '1', unit_price: '10', line_total: '10',
  }))
  const components = sales.map((sale) => ({
    id: `component-${sale.id}`, transaction_id: sale.id, product_id: 'powder',
    quantity: '80', cost_total: '4',
  }))
  const client = fakeClient({
    stalls: [{ id: 'main', name: 'Main', code: 'MAIN', overhead_config: [] }],
    transactions: [...sales, { ...sales[0], id: 'other-sale', stall_id: 'other' }],
    transaction_items: [...items, {
      id: 'other-item', transaction_id: 'other-sale', product_id: 'cone',
      product_name: 'Other', quantity: '1', unit_price: '10', line_total: '10',
    }],
    sale_components: [...components, { id: 'other-component', transaction_id: 'other-sale', product_id: 'powder', quantity: '80', cost_total: '4' }],
    business_days: [{ id: 'day-1', stall_id: 'main', business_date: '2026-10-06' }],
    sale_reversals: [{ id: 'reversal-1', stall_id: 'main', transaction_id: 'sale-0000',
      original_day_id: 'day-1', payout_day_id: 'day-1', cashier_id: 'cashier-1',
      kind: 'refund', reason: 'Customer return', restock: true,
      cash_returned: '10', occurred_at: '2026-10-06T01:00:00Z' }],
  })

  const workspace = await loadWorkspace(client, 'main')

  assert.equal(workspace.transactions.length, 1201)
  assert.equal(workspace.transactionItems.length, 1201)
  assert.equal(workspace.transactionItems.some((item) => item.id === 'other-item'), false)
  assert.equal(workspace.saleComponents?.length, 1201)
  assert.equal(workspace.saleComponents?.some((item) => item.id === 'other-component'), false)
  assert.equal(workspace.saleReversals?.[0].cash_returned, 10)
  assert.equal(workspace.transactions[0].id, 'sale-1200')
  assert.equal(workspace.transactions.at(-1)?.id, 'sale-0000')
})
