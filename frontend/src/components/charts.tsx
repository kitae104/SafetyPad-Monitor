/** Small dependency-free SVG charts. Each exposes its data as text for screen readers. */

/** @param onSelectDay when given, each day's point is a keyboard-reachable link (e.g. to that day's events). */
export function ViolationTrendChart({ data, onSelectDay }: { data: { date: string; count: number }[]; onSelectDay?: (date: string) => void }) {
  const W = 560
  const H = 200
  const pad = { l: 36, r: 12, t: 12, b: 28 }
  const max = Math.max(4, ...data.map((d) => d.count))
  const niceMax = Math.ceil(max / 4) * 4
  const x = (i: number) => pad.l + (i * (W - pad.l - pad.r)) / Math.max(1, data.length - 1)
  const y = (v: number) => pad.t + (1 - v / niceMax) * (H - pad.t - pad.b)
  const pts = data.map((d, i) => `${x(i)},${y(d.count)}`).join(' ')
  const area = data.length ? `${x(0)},${y(0)} ${pts} ${x(data.length - 1)},${y(0)}` : ''
  const summary = data.map((d) => `${d.date} ${d.count}건`).join(', ')
  return (
    <figure className="m-0">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`최근 7일 일자별 위반 건수: ${summary}`} className="h-auto w-full">
        {[0, 1, 2, 3, 4].map((t) => {
          const v = (niceMax / 4) * t
          return (
            <g key={t}>
              <line x1={pad.l} x2={W - pad.r} y1={y(v)} y2={y(v)} stroke="#e2e8f0" />
              <text x={pad.l - 6} y={y(v) + 4} textAnchor="end" fontSize="11" fill="#64748b">{v}</text>
            </g>
          )
        })}
        <polygon points={area} fill="#2563eb" opacity="0.08" />
        <polyline points={pts} fill="none" stroke="#2563eb" strokeWidth="2.5" strokeLinejoin="round" />
        {data.map((d, i) => (
          <g key={d.date} data-testid={onSelectDay ? 'trend-day' : undefined} {...(onSelectDay ? {
            role: 'link', tabIndex: 0, 'aria-label': `${d.date} 위반 ${d.count}건, 이 날의 이벤트 보기`, className: 'cursor-pointer outline-none [&:focus-visible_circle]:stroke-[4] hover:[&_circle]:fill-blue-100',
            onClick: () => onSelectDay(d.date), onKeyDown: (e: React.KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelectDay(d.date) } },
          } : {})}>
            {onSelectDay && <rect x={x(i) - 22} y={pad.t} width="44" height={H - pad.t} fill="transparent" />}
            <circle cx={x(i)} cy={y(d.count)} r="4" fill="#fff" stroke="#2563eb" strokeWidth="2" />
            <text x={x(i)} y={H - 8} textAnchor="middle" fontSize="11" fill="#64748b">{d.date.slice(5)}</text>
            <title>{`${d.date}: ${d.count}건`}</title>
          </g>
        ))}
      </svg>
      <figcaption className="sr-only">{summary}</figcaption>
    </figure>
  )
}

export function BarList({ rows, unit = '건', empty, onSelect, selectHint }: {
  rows: { label: string; sub?: string; value: number; key?: string }[]
  unit?: string
  empty: string
  /** When given, every row is a button (e.g. open that device's events). */
  onSelect?: (row: { label: string; key?: string }) => void
  selectHint?: (row: { label: string }) => string
}) {
  const max = Math.max(1, ...rows.map((r) => r.value))
  if (rows.length === 0) return <p className="py-6 text-center text-sm text-slate-500">{empty}</p>
  return (
    <ol className="space-y-3">
      {rows.map((r) => (
        <li key={r.label}>
          {onSelect ? (
            <button type="button" onClick={() => onSelect(r)} aria-label={`${r.label} ${r.value}${unit}. ${selectHint?.(r) ?? '자세히 보기'}`}
              className="-mx-2 block min-h-11 w-[calc(100%+1rem)] rounded-lg px-2 py-1 text-left hover:bg-blue-50 focus-visible:outline-2 focus-visible:outline-brand">
              <Row r={r} unit={unit} max={max} />
            </button>
          ) : <Row r={r} unit={unit} max={max} />}
        </li>
      ))}
    </ol>
  )
}

function Row({ r, unit, max }: { r: { label: string; sub?: string; value: number }; unit: string; max: number }) {
  return (
    <>
      <div className="mb-1 flex items-baseline justify-between gap-2 text-sm">
        <span className="min-w-0 truncate font-medium text-slate-800">
          {r.label}
          {r.sub && <span className="ml-2 text-xs font-normal text-slate-500">{r.sub}</span>}
        </span>
        <span className="tabular-nums text-slate-600">{r.value}{unit}</span>
      </div>
      <div className="h-2.5 overflow-hidden rounded-full bg-slate-100" aria-hidden="true">
        <div className="h-full rounded-full bg-brand" style={{ width: `${(r.value / max) * 100}%` }} />
      </div>
    </>
  )
}
