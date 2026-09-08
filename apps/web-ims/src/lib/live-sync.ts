export const LIVE_REFRESH_INTERVAL_MS = 3_000

type RefreshWindow = Pick<Window, 'setInterval' | 'clearInterval' | 'addEventListener' | 'removeEventListener'>
type RefreshDocument = Pick<Document, 'visibilityState' | 'addEventListener' | 'removeEventListener'>

export function startWorkspaceAutoRefresh(
  refresh: () => void | Promise<void>,
  browserWindow: RefreshWindow = window,
  browserDocument: RefreshDocument = document,
  intervalMs = LIVE_REFRESH_INTERVAL_MS,
) {
  const refreshWhenVisible = () => {
    if (browserDocument.visibilityState === 'visible') void refresh()
  }

  const intervalId = browserWindow.setInterval(refreshWhenVisible, intervalMs)
  browserWindow.addEventListener('focus', refreshWhenVisible)
  browserDocument.addEventListener('visibilitychange', refreshWhenVisible)

  return () => {
    browserWindow.clearInterval(intervalId)
    browserWindow.removeEventListener('focus', refreshWhenVisible)
    browserDocument.removeEventListener('visibilitychange', refreshWhenVisible)
  }
}
