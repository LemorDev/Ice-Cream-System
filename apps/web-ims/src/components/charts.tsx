import { useId } from 'react'

export type TrendPoint = {
  date: string
  value: number
}

const compactNumber = new Intl.NumberFormat('en-PH', { notation: 'compact', maximumFractionDigits: 1 })
const shortDate = new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', timeZone: 'UTC' })

function displayDate(date: string) {
  return shortDate.format(new Date(`${date}T00:00:00Z`))
}

export function RevenueTrendChart({ points, formatValue }: { points: TrendPoint[]; formatValue: (value: number) => string }) {
  const gradientId = useId().replaceAll(':', '')
  const width = 720
  const height = 260
  const paddingX = 42
  const paddingTop = 22
  const paddingBottom = 38
  const chartWidth = width - paddingX * 2
  const chartHeight = height - paddingTop - paddingBottom
  const highestValue = Math.max(...points.map((point) => point.value), 0)
  const maxValue = Math.max(highestValue, 1)
  const coordinates = points.map((point, index) => ({
    ...point,
    x: paddingX + (points.length === 1 ? chartWidth / 2 : (index / (points.length - 1)) * chartWidth),
    y: paddingTop + chartHeight - (point.value / maxValue) * chartHeight,
  }))
  const line = coordinates.map((point) => `${point.x},${point.y}`).join(' ')
  const area = coordinates.length > 0
    ? `${paddingX},${paddingTop + chartHeight} ${line} ${paddingX + chartWidth},${paddingTop + chartHeight}`
    : ''
  const labelIndexes = [...new Set([0, Math.floor((points.length - 1) / 2), points.length - 1])]

  return (
    <div className="relative min-h-56 w-full" role="img" aria-label={`Revenue trend. Highest value ${formatValue(highestValue)}.`}>
      <svg className="h-auto w-full overflow-visible" viewBox={`0 0 ${width} ${height}`}>
        <defs>
          <linearGradient id={gradientId} x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="#7c3aed" stopOpacity="0.3" />
            <stop offset="100%" stopColor="#7c3aed" stopOpacity="0" />
          </linearGradient>
        </defs>
        {[0, 0.5, 1].map((ratio) => {
          const y = paddingTop + chartHeight * ratio
          const value = maxValue * (1 - ratio)
          return <g key={ratio}><line stroke="#eadcff" strokeDasharray="5 6" x1={paddingX} x2={paddingX + chartWidth} y1={y} y2={y} /><text fill="#8b7b9e" fontSize="11" textAnchor="end" x={paddingX - 8} y={y + 4}>{compactNumber.format(value)}</text></g>
        })}
        {area && <polygon fill={`url(#${gradientId})`} points={area} />}
        {line && <polyline fill="none" points={line} stroke="#6d28d9" strokeLinecap="round" strokeLinejoin="round" strokeWidth="4" />}
        {coordinates.map((point) => <circle key={point.date} cx={point.x} cy={point.y} fill="#fffdf8" r="5" stroke="#6d28d9" strokeWidth="3"><title>{displayDate(point.date)}: {formatValue(point.value)}</title></circle>)}
        {labelIndexes.map((index) => points[index] && <text fill="#746681" fontSize="12" key={points[index].date} textAnchor={index === 0 ? 'start' : index === points.length - 1 ? 'end' : 'middle'} x={coordinates[index].x} y={height - 8}>{displayDate(points[index].date)}</text>)}
      </svg>
      {points.every((point) => point.value === 0) && <p className="absolute inset-0 flex items-center justify-center text-sm text-slate-400">No completed sales in this period</p>}
    </div>
  )
}

export function HorizontalBarChart({ items, formatValue }: { items: Array<{ label: string; value: number; detail?: string }>; formatValue: (value: number) => string }) {
  const maximum = Math.max(...items.map((item) => item.value), 1)
  if (items.length === 0) return <p className="py-10 text-center text-sm text-slate-400">No completed product sales in this period</p>
  return (
    <div className="space-y-4" role="list">
      {items.map((item, index) => <div key={item.label} role="listitem">
        <div className="mb-1.5 flex items-end justify-between gap-3 text-sm">
          <div className="min-w-0"><p className="truncate font-semibold text-[#39235f]"><span className="mr-2 text-xs text-slate-400">{index + 1}</span>{item.label}</p>{item.detail && <p className="mt-0.5 text-xs text-slate-400">{item.detail}</p>}</div>
          <span className="shrink-0 font-bold text-[#5a1bb0]">{formatValue(item.value)}</span>
        </div>
        <div className="h-2.5 overflow-hidden rounded-full bg-[#eee7f5]"><div className="h-full rounded-full bg-[linear-gradient(90deg,_#5b21b6_0%,_#a855f7_100%)]" style={{ width: `${Math.max(4, (item.value / maximum) * 100)}%` }} /></div>
      </div>)}
    </div>
  )
}
