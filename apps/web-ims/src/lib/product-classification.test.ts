import assert from 'node:assert/strict'
import test from 'node:test'
import { isStockable, productMatchesType, productTypeLabel } from './product-classification.ts'

test('only raw ingredients and packaging can be received or adjusted', () => {
  assert.equal(isStockable({ product_type: 'sellable' }), false)
  assert.equal(isStockable({ product_type: 'raw' }), true)
  assert.equal(isStockable({ product_type: 'packaging' }), true)
})

test('catalog filters match the persisted classifications', () => {
  assert.equal(productMatchesType({ product_type: 'packaging' }, 'all'), true)
  assert.equal(productMatchesType({ product_type: 'packaging' }, 'packaging'), true)
  assert.equal(productMatchesType({ product_type: 'packaging' }, 'raw'), false)
  assert.equal(productTypeLabel('raw'), 'Raw ingredient')
})
