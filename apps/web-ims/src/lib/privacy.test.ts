import assert from 'node:assert/strict'
import test from 'node:test'
import { formatFinancialAmount } from './privacy.ts'

test('financial privacy masks amounts without changing their value', () => {
  assert.equal(formatFinancialAmount(1250.5, false), '₱••••')
  assert.match(formatFinancialAmount(1250.5, true), /1,250\.50/)
})
