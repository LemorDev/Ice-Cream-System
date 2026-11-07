import { useState } from 'react'
import { Area, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

export type TrendPoint = {
  date: string
  value: number
  orders: number
}

const shortDate = new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', timeZone: 'UTC' })

function displayDate(date: string) {
  return shortDate.format(new Date(`${date}T00:00:00Z`))
}

export function RevenueTrendChart({ points, formatValue, showRevenue = true }: { points: TrendPoint[]; formatValue: (value: number) => string; showRevenue?: boolean }) {
  const [selectedDate, setSelectedDate] = useState<string | null>(null)
  const highestValue = Math.max(...points.map((point) => point.value), 0)
  const selected = points.find((point) => point.date === selectedDate) ?? points.at(-1)
  if (points.length === 0) return <p className="py-16 text-center text-sm text-slate-500">No dates in this period</p>

  return (
    <div className="w-full" role="region" aria-label={`Daily revenue and completed orders chart. Highest revenue ${formatValue(highestValue)}.`}>
      <div className="mb-3 flex flex-wrap items-center gap-x-5 gap-y-2 text-sm text-slate-600" aria-label="Chart legend">
        <span className="inline-flex items-center gap-2"><span className={`h-2.5 w-5 rounded-full ${showRevenue ? 'bg-violet-700' : 'bg-slate-300'}`} />{showRevenue ? 'Revenue · left axis' : 'Revenue hidden'}</span>
        <span className="inline-flex items-center gap-2"><span className="h-2.5 w-5 rounded-full bg-teal-600" />Completed orders · right axis</span>
      </div>
      <div className="h-64 w-full sm:h-72">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart accessibilityLayer data={points} margin={{ top: 16, right: 0, bottom: 4, left: 0 }}
            onClick={(state) => { if (typeof state?.activeLabel === 'string') setSelectedDate(state.activeLabel) }}>
            <defs>
              <linearGradient id="revenue-area-gradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#7c3aed" stopOpacity={0.2} />
                <stop offset="100%" stopColor="#7c3aed" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke="#e9e4ef" strokeDasharray="3 5" vertical={false} />
            <XAxis dataKey="date" tickFormatter={displayDate} tickLine={false} axisLine={false} tick={{ fill: '#64748b', fontSize: 12 }} minTickGap={24} dy={10} />
            <YAxis yAxisId="revenue" hide={!showRevenue} width={75} domain={[0, (dataMax: number) => Math.max(1, Math.ceil(dataMax * 1.15))]} tickFormatter={formatValue} tickLine={false} axisLine={false} tick={{ fill: '#64748b', fontSize: 12 }} tickCount={5} />
            <YAxis yAxisId="orders" orientation="right" width={32} allowDecimals={false} domain={[0, 'dataMax + 1']} tickLine={false} axisLine={false} tick={{ fill: '#0f766e', fontSize: 12 }} tickCount={4} />
            <Tooltip
              labelFormatter={(label) => displayDate(String(label))}
              formatter={(value, name) => [name === 'Completed orders' ? Number(value ?? 0).toLocaleString() : formatValue(Number(value ?? 0)), name]}
              contentStyle={{ border: '1px solid #e9d5ff', borderRadius: 12, boxShadow: '0 12px 30px rgba(52, 21, 89, 0.12)', color: '#220046' }}
              cursor={{ stroke: '#a78bfa', strokeDasharray: '4 4' }}
            />
            {showRevenue && <Area yAxisId="revenue" dataKey="value" type="monotone" stroke="none" fill="url(#revenue-area-gradient)" tooltipType="none" legendType="none" isAnimationActive={false} />}
            {showRevenue && <Line yAxisId="revenue" dataKey="value" name="Revenue" type="monotone" stroke="#6d28d9" strokeWidth={3} dot={points.length <= 14 ? { r: 3.5, strokeWidth: 2, fill: '#fff' } : false} activeDot={{ r: 6, stroke: '#fff', strokeWidth: 2 }} isAnimationActive={false} />}
            <Line yAxisId="orders" dataKey="orders" name="Completed orders" type="monotone" stroke="#0f766e" strokeWidth={2.5} dot={points.length <= 14 ? { r: 3, strokeWidth: 1.5, fill: '#fff' } : false} activeDot={{ r: 5, stroke: '#fff', strokeWidth: 2 }} isAnimationActive={false} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-3 rounded-xl border border-[#eadcff] bg-[#fbf8ff] px-3 py-2 text-sm" aria-live="polite">
        <label className="font-semibold text-[#39235f]" htmlFor="trend-day">Inspect day</label>
        <select id="trend-day" className="rounded-lg border border-[#dfd4f3] bg-white px-2 py-1" value={selected?.date ?? ''} onChange={(event) => setSelectedDate(event.target.value)}>
          {points.map((point) => <option key={point.date} value={point.date}>{displayDate(point.date)}</option>)}
        </select>
        <span className="text-slate-600">Revenue <strong className="text-[#5a1bb0]">{formatValue(selected?.value ?? 0)}</strong></span>
        <span className="text-slate-600">Orders <strong className="text-teal-700">{selected?.orders ?? 0}</strong></span>
      </div>
      {points.every((point) => point.value === 0) && <p className="pt-1 text-center text-sm text-slate-500">No completed sales in this period</p>}
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
