import assert from 'node:assert/strict'
import test from 'node:test'
import { canAccessWebView, getDefaultView, getNavigation, getViewLabel } from './access.ts'

test('system administrators receive a separate administration landing page', () => {
  assert.equal(getDefaultView('system_admin'), 'admin')
  assert.equal(canAccessWebView('system_admin', 'admin'), true)
  assert.equal(getNavigation('system_admin')[0].label, 'Administration')
})

test('owners cannot open or navigate to system administration', () => {
  assert.equal(getDefaultView('owner'), 'overview')
  assert.equal(canAccessWebView('owner', 'admin'), false)
  assert.equal(getNavigation('owner').flatMap((group) => group.items).includes('admin'), false)
})

test('cashiers receive no web dashboard routes', () => {
  assert.deepEqual(getNavigation('cashier'), [])
  assert.equal(canAccessWebView('cashier', 'overview'), false)
})

test('administrative and owner navigation use role-specific labels', () => {
  assert.equal(getViewLabel('system_admin', 'staff'), 'Users & access')
  assert.equal(getViewLabel('owner', 'staff'), 'Cashiers & POS')
  assert.equal(getViewLabel('system_admin', 'stall'), 'Stall administration')
  assert.equal(getViewLabel('owner', 'stall'), 'Costs & settings')
})
