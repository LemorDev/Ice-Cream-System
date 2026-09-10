export type TrendPoint = {
  date: string
  value: number
}

const shortDate = new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', timeZone: 'UTC' })

function displayDate(date: string) {
  return shortDate.format(new Date(`${date}T00:00:00Z`))
}

export function RevenueTrendChart({ points, formatValue }: { points: TrendPoint[]; formatValue: (value: number) => string }) {
  const width = 720
  const height = 300
  const paddingLeft = 82
  const paddingRight = 18
  const paddingTop = 20
  const paddingBottom = 46
  const chartWidth = width - paddingLeft - paddingRight
  const chartHeight = height - paddingTop - paddingBottom
  const highestValue = Math.max(...points.map((point) => point.value), 0)
  const maxValue = Math.max(highestValue, 1)
  const slotWidth = points.length > 0 ? chartWidth / points.length : chartWidth
  const barWidth = Math.max(2, Math.min(34, slotWidth * 0.62))
  const labelIndexes = [...new Set([0, Math.floor((points.length - 1) / 4), Math.floor((points.length - 1) / 2), Math.floor(((points.length - 1) * 3) / 4), points.length - 1])]

  return (
    <div className="relative min-h-60 w-full" role="img" aria-label={`Daily revenue chart. Highest value ${formatValue(highestValue)}.`}>
      <svg className="h-auto w-full" viewBox={`0 0 ${width} ${height}`}>
        {[0, 0.25, 0.5, 0.75, 1].map((ratio) => {
          const y = paddingTop + chartHeight * ratio
          const value = maxValue * (1 - ratio)
          return <g key={ratio}><line stroke="#e5e7eb" x1={paddingLeft} x2={paddingLeft + chartWidth} y1={y} y2={y} /><text fill="#64748b" fontSize="12" textAnchor="end" x={paddingLeft - 10} y={y + 4}>{formatValue(value)}</text></g>
        })}
        {points.map((point, index) => {
          const barHeight = point.value === 0 ? 0 : Math.max(3, (point.value / maxValue) * chartHeight)
          const x = paddingLeft + index * slotWidth + (slotWidth - barWidth) / 2
          const y = paddingTop + chartHeight - barHeight
          return <rect key={point.date} x={x} y={y} width={barWidth} height={barHeight} rx={Math.min(5, barWidth / 3)} fill="#6d28d9"><title>{displayDate(point.date)}: {formatValue(point.value)}</title></rect>
        })}
        {labelIndexes.map((index) => points[index] && <text fill="#64748b" fontSize="12" key={points[index].date} textAnchor="middle" x={paddingLeft + index * slotWidth + slotWidth / 2} y={height - 12}>{displayDate(points[index].date)}</text>)}
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
