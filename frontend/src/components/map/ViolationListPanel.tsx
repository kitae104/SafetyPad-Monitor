import { Link } from 'react-router-dom'
import { ArrowLeft, MapPin } from 'lucide-react'
import type { MapEventPoint } from '../../api/types'
import { formatGps, formatKst } from '../../lib/format'
import { groupByDevice, violationsOf } from '../../lib/mapSummary'
import { EmptyState } from '../ui'

const MAX_ROWS = 100

/**
 * Text counterpart of the map: the same violations as a list, so the numbers on the map badges can be read, searched
 * and clicked. Without a selection it ranks devices by violation count; with one it lists that device's violations
 * (newest first) and each row can be shown on the map.
 */
export function ViolationListPanel({ points, selectedDeviceId, focusPointId, onSelectDevice, onFocusPoint, from, to }: {
  points: MapEventPoint[]
  selectedDeviceId: string | null
  focusPointId: number | null
  onSelectDevice: (id: string | null) => void
  onFocusPoint: (id: number) => void
  from: string
  to: string
}) {
  const eventsLink = (deviceId?: string) => {
    const q = new URLSearchParams()
    if (deviceId) q.set('device_id', deviceId)
    if (from) q.set('from', from)
    if (to) q.set('to', to)
    const s = q.toString()
    return `/events${s ? `?${s}` : ''}`
  }

  if (points.length === 0) {
    return <EmptyState title="표시할 위반 위치가 없습니다" hint="기간을 넓히거나, GPS가 포함된 위반이 수신되면 여기에 나타납니다." />
  }

  if (selectedDeviceId) {
    const mine = violationsOf(points, selectedDeviceId)
    return (
      <div data-testid="violation-list-device">
        <button type="button" onClick={() => onSelectDevice(null)} className="mb-2 inline-flex min-h-11 items-center gap-1 text-sm font-medium text-brand hover:underline"><ArrowLeft size={16} aria-hidden="true" />전체 장비 목록</button>
        <p className="text-sm font-semibold text-slate-900">{selectedDeviceId} 위반 {mine.length}건 <span className="font-normal text-slate-500">(최신순)</span></p>
        {mine.length === 0 ? (
          <p className="mt-2 text-sm text-slate-600">이 기간에 지도에 표시할 위반 위치가 없습니다.</p>
        ) : (
          <ol className="mt-2 max-h-96 divide-y divide-slate-100 overflow-auto rounded-lg border border-slate-200" aria-label={`${selectedDeviceId} 위반 목록`}>
            {mine.slice(0, MAX_ROWS).map((p) => (
              <li key={p.id}>
                <button type="button" onClick={() => onFocusPoint(p.id)} aria-pressed={focusPointId === p.id}
                  className={`flex min-h-11 w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-brand ${focusPointId === p.id ? 'bg-blue-50' : ''}`}>
                  <MapPin size={16} aria-hidden="true" className={focusPointId === p.id ? 'text-red-600' : 'text-slate-400'} />
                  <span className="min-w-0 flex-1">
                    <span className="block font-medium tabular-nums text-slate-900">{formatKst(p.event_time)}</span>
                    <span className="block text-xs tabular-nums text-slate-500">{formatGps(p.latitude, p.longitude)}</span>
                  </span>
                  <span className="shrink-0 text-xs font-medium text-brand">지도에서 보기</span>
                </button>
              </li>
            ))}
          </ol>
        )}
        {mine.length > MAX_ROWS && <p className="mt-1 text-xs text-slate-500">최신 {MAX_ROWS}건만 표시합니다. 전체는 이벤트 목록에서 확인하세요.</p>}
        <Link className="mt-2 inline-flex min-h-11 items-center text-sm font-medium text-brand underline" to={eventsLink(selectedDeviceId)}>이 장비의 이벤트 목록·CSV</Link>
      </div>
    )
  }

  const groups = groupByDevice(points)
  const max = groups[0]?.count ?? 1
  return (
    <div data-testid="violation-list-all">
      <p className="text-sm text-slate-700">위반 <b>{points.length.toLocaleString()}건</b> · 장비 <b>{groups.length}대</b> <span className="text-slate-500">(위반이 많은 순)</span></p>
      <ol className="mt-2 max-h-96 divide-y divide-slate-100 overflow-auto rounded-lg border border-slate-200" aria-label="장비별 위반 현황">
        {groups.map((g) => (
          <li key={g.device_id}>
            <button type="button" onClick={() => onSelectDevice(g.device_id)} className="block min-h-11 w-full px-3 py-2 text-left hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-brand">
              <span className="flex items-baseline justify-between gap-2 text-sm">
                <span className="min-w-0 truncate font-medium text-slate-900">{g.device_id}{g.alias && <span className="ml-1 font-normal text-slate-500">{g.alias}</span>}</span>
                <span className="shrink-0 font-semibold tabular-nums text-blue-800">{g.count}건</span>
              </span>
              <span aria-hidden="true" className="mt-1 block h-1.5 rounded bg-slate-100"><span className="block h-full rounded bg-blue-600" style={{ width: `${Math.max(4, (g.count / max) * 100)}%` }} /></span>
              <span className="mt-1 block text-xs tabular-nums text-slate-500">최근 위반 {formatKst(g.latest)}</span>
            </button>
          </li>
        ))}
      </ol>
      <p className="mt-1 text-xs text-slate-500">장비를 누르면 지도가 그 장비의 위반 위치로 맞춰지고, 위반 시각별 목록이 열립니다.</p>
      <Link className="mt-1 inline-flex min-h-11 items-center text-sm font-medium text-brand underline" to={eventsLink()}>전체 이벤트 목록·CSV</Link>
    </div>
  )
}
