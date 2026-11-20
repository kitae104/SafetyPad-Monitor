import { X } from 'lucide-react'
import type { MapEventPoint } from '../../api/types'
import { formatGps, formatKst } from '../../lib/format'
import { summarizeCluster } from '../../lib/mapSummary'

/** What the map is currently describing: one violation point, or the contents of a cluster badge. */
export type MapInfo = { kind: 'point'; point: MapEventPoint } | { kind: 'cluster'; points: MapEventPoint[] }

/**
 * Caption card over the map. Hover/focus shows a transient (non-interactive) card; clicking a marker pins it
 * so the details stay readable and can be dismissed. The cluster card answers "what is inside 위반 N건?".
 */
export function MapInfoCard({ info, pinned, selectedDeviceId, onSelectDevice, onClose, className = '' }: {
  info: MapInfo
  pinned: boolean
  selectedDeviceId: string | null
  onSelectDevice: (id: string) => void
  onClose: () => void
  className?: string
}) {
  const box = `rounded-lg bg-slate-900/95 px-3 py-2 text-xs text-white shadow-lg ${pinned ? '' : 'pointer-events-none'} ${className}`
  if (info.kind === 'cluster') {
    const s = summarizeCluster(info.points)
    return (
      <div role="status" data-testid="map-info-cluster" className={box}>
        <p className="text-sm font-bold">위반 {s.count}건 · 장비 {s.deviceCount}대</p>
        <p className="mt-0.5 text-slate-300">{formatKst(s.earliest).slice(0, 16)} ~ {formatKst(s.latest).slice(0, 16)}</p>
        <ul className="mt-1 space-y-0.5">
          {s.top.map((t) => <li key={t.device_id} className="tabular-nums"><b>{t.device_id}</b> {t.count}건</li>)}
          {s.deviceCount > s.top.length && <li className="text-slate-300">외 {s.deviceCount - s.top.length}대</li>}
        </ul>
        <p className="mt-1 text-slate-300">누르면 확대되어 개별 위치가 보입니다</p>
      </div>
    )
  }
  const p = info.point
  return (
    <div role="status" data-testid="map-info-point" className={box}>
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm font-bold">{p.device_id}{p.alias ? <span className="ml-1 font-normal text-slate-300">({p.alias})</span> : null}{p.simulated && <span className="ml-2 rounded bg-amber-300 px-1 text-[10px] font-bold text-slate-900">가상 데이터</span>}</p>
        {pinned && (
          <button type="button" onClick={onClose} aria-label="정보 닫기" className="-m-2 inline-flex size-11 shrink-0 items-center justify-center rounded text-slate-300 hover:text-white focus-visible:outline-2 focus-visible:outline-white"><X size={16} aria-hidden="true" /></button>
        )}
      </div>
      <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5 tabular-nums">
        <dt className="text-slate-300">위반 발생</dt><dd>{formatKst(p.event_time)}</dd>
        <dt className="text-slate-300">최초 수신</dt><dd>{formatKst(p.received_at)}</dd>
        <dt className="text-slate-300">위치</dt><dd>{formatGps(p.latitude, p.longitude)}</dd>
      </dl>
      {pinned && selectedDeviceId !== p.device_id && (
        <button type="button" onClick={() => onSelectDevice(p.device_id)} className="mt-2 min-h-11 rounded bg-white px-3 font-semibold text-slate-900 hover:bg-slate-100">이 장비의 위반만 보기</button>
      )}
    </div>
  )
}

/** Legend that explains every symbol on the map in plain Korean, including what the numbers in the blue badges mean. */
export function MapLegend({ route = false }: { route?: boolean }) {
  return (
    <ul className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-700" aria-label="범례" data-testid="map-legend">
      <li className="flex items-center gap-1"><span aria-hidden="true" className="inline-block size-3 rotate-45 bg-orange-600" />위반 지점 1건</li>
      <li className="flex items-center gap-1"><span aria-hidden="true" className="inline-block h-4 rounded-full bg-blue-700 px-2 text-[10px] font-bold leading-4 text-white">위반 N건</span>가까운 위반 묶음 (누르면 확대)</li>
      <li className="flex items-center gap-1"><span aria-hidden="true" className="inline-block size-3 rotate-45 bg-red-600" />선택한 장비의 위반</li>
      {route && <li className="flex items-center gap-1"><span aria-hidden="true" className="inline-block size-3 rounded-full border-2 border-cyan-600 bg-white" />정기 위치</li>}
      {route && <li className="flex items-center gap-1"><span aria-hidden="true" className="inline-block h-0 w-5 border-t-2 border-dashed border-slate-400" />수집 간격이 큰 구간</li>}
    </ul>
  )
}
