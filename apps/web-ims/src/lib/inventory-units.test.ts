import assert from 'node:assert/strict'
import test from 'node:test'
import { receivedBaseUnits, targetBaseUnits } from './inventory-units.ts'

test('ten one-kilogram powder packs become ten thousand grams', () => {
  assert.equal(receivedBaseUnits(10, { pack_size: 1000, conversion_rate: 1 }), 10_000)
})

test('packaging and fractional packs convert into base units', () => {
  assert.equal(receivedBaseUnits(10, { pack_size: 50, conversion_rate: 1 }), 500)
  assert.equal(receivedBaseUnits(0.5, { pack_size: 1000, conversion_rate: 1 }), 500)
})

test('invalid packs and quantities too small for the ledger are rejected', () => {
  assert.throws(() => receivedBaseUnits(10, { pack_size: 0, conversion_rate: 1 }))
  assert.throws(() => receivedBaseUnits(0, { pack_size: 1000, conversion_rate: 1 }))
  assert.throws(() => receivedBaseUnits(0.001, { pack_size: 0.001, conversion_rate: 1 }))
})

test('setting a ten-pack target from ten grams requires a 9990 gram correction', () => {
  const target = targetBaseUnits(10, 'packs', { pack_size: 1000, conversion_rate: 1 })
  assert.equal(target, 10_000)
  assert.equal(target - 10, 9_990)
  assert.equal(targetBaseUnits(0, 'packs', { pack_size: 1000, conversion_rate: 1 }), 0)
})
