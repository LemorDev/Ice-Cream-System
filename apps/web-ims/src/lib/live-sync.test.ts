import assert from 'node:assert/strict'
import test from 'node:test'
import { startWorkspaceAutoRefresh } from './live-sync.ts'

test('auto refresh runs on its interval and browser focus while visible', () => {
  let refreshes = 0
  let intervalCallback: (() => void) | undefined
  const windowListeners = new Map<string, () => void>()
  const documentListeners = new Map<string, () => void>()
  const fakeWindow = {
    setInterval(callback: () => void) { intervalCallback = callback; return 7 },
    clearInterval() {},
    addEventListener(name: string, callback: () => void) { windowListeners.set(name, callback) },
    removeEventListener(name: string) { windowListeners.delete(name) },
  }
  const fakeDocument = {
    visibilityState: 'visible',
    addEventListener(name: string, callback: () => void) { documentListeners.set(name, callback) },
    removeEventListener(name: string) { documentListeners.delete(name) },
  }

  const stop = startWorkspaceAutoRefresh(
    () => { refreshes += 1 },
    fakeWindow as never,
    fakeDocument as never,
  )
  intervalCallback?.()
  windowListeners.get('focus')?.()

  assert.equal(refreshes, 2)
  stop()
  assert.equal(windowListeners.size, 0)
  assert.equal(documentListeners.size, 0)
})

test('auto refresh does not query the IMS while the tab is hidden', () => {
  let refreshes = 0
  let intervalCallback: (() => void) | undefined
  const fakeWindow = {
    setInterval(callback: () => void) { intervalCallback = callback; return 9 },
    clearInterval() {},
    addEventListener() {},
    removeEventListener() {},
  }
  const fakeDocument = {
    visibilityState: 'hidden',
    addEventListener() {},
    removeEventListener() {},
  }

  startWorkspaceAutoRefresh(
    () => { refreshes += 1 },
    fakeWindow as never,
    fakeDocument as never,
  )
  intervalCallback?.()

  assert.equal(refreshes, 0)
})
