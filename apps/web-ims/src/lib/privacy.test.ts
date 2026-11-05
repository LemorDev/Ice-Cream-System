import assert from 'node:assert/strict'
import test from 'node:test'
import { formatFinancialAmount, formatUnitCostAmount } from './privacy.ts'

test('financial privacy masks amounts without changing their value', () => {
  assert.equal(formatFinancialAmount(1250.5, false), '₱••••')
  assert.match(formatFinancialAmount(1250.5, true), /1,250\.50/)
})

test('unit cost formatting preserves precision for small per-gram and per-ml values', () => {
  assert.equal(formatUnitCostAmount(0.185, false), '₱••••')
  assert.match(formatUnitCostAmount(0.185, true), /0\.185/)
  assert.match(formatUnitCostAmount(0.0025, true), /0\.0025/)
  assert.match(formatUnitCostAmount(15.5, true), /15\.50/)
  assert.match(formatUnitCostAmount(0, true), /0\.00/)
})
