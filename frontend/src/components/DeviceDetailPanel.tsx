import type { DeviceDetail } from '../api/types'
import { useApi } from '../lib/useApi'
import { formatGps, formatKst } from '../lib/format'
import { BatteryGauge, EmptyState, ErrorNotice, LastSeenIndicator, LoadingSkeleton, btnSecondary } from './ui'
import { Link } from 'react-router-dom'
import { MapPin } from 'lucide-react'

/** Selected-device summary. "마지막 위반 위치" is a past violation point, never a live position. */
export function DeviceDetailPanel({ deviceId, onClear }: { deviceId: string | null; onClear: () => void }) {
  const { data, error, loading, reload } = useApi<DeviceDetail>(deviceId ? `/devices/${encodeURIComponent(deviceId)}` : null)
  if (!deviceId) {
    return <EmptyState icon={<MapPin size={28} />} title="장비를 선택해 주세요" hint="지도의 마커나 이벤트 상세에서 장비를 선택하면 상세가 표시됩니다." />
  }
  if (error) return <ErrorNotice message={error} onRetry={reload} />
  if (loading || !data) return <LoadingSkeleton rows={5} />
  const d = data.device
  return (
    <div data-testid="device-panel">
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="text-lg font-bold text-slate-900">{d.device_id}</p>
          <p className="text-sm text-slate-500">{d.alias ?? '별칭 없음'} · {d.zone ?? '구역 미지정'}</p>
        </div>
        <button type="button" onClick={onClear} className={btnSecondary}>선택 해제</button>
      </div>
      <dl className="mt-4 space-y-3 text-sm">
        <div><dt className="text-slate-500">최근 수신</dt><dd className="mt-1"><LastSeenIndicator iso={d.last_received_at} /></dd><dd className="mt-1 text-xs tabular-nums text-slate-500">{formatKst(d.last_received_at)}</dd></div>
        <div><dt className="text-slate-500">배터리 (마지막 보고)</dt><dd className="mt-1"><BatteryGauge level={d.last_battery_level} /></dd></div>
        <div><dt className="text-slate-500">누적 위반 이벤트</dt><dd className="font-semibold">{d.event_count}건</dd></div>
        <div>
          <dt className="text-slate-500">마지막 위반 위치 <span className="text-xs">(과거 이벤트 지점, 실시간 위치 아님)</span></dt>
          <dd className="mt-1 tabular-nums">
            {d.last_known_gps ? <>{formatGps(d.last_known_gps.latitude, d.last_known_gps.longitude)}<br /><span className="text-xs text-slate-500">{formatKst(d.last_known_gps.event_time)} 기록</span></> : <span className="text-slate-400">GPS가 있는 이벤트 없음</span>}
          </dd>
        </div>
      </dl>
      <div className="mt-4 flex flex-wrap gap-x-4 border-t border-slate-100 pt-2 text-sm font-medium">
        <Link className="inline-flex min-h-11 items-center text-brand underline" to={`/events?device_id=${encodeURIComponent(d.device_id)}`}>이 장비의 이벤트 보기</Link>
        <Link className="inline-flex min-h-11 items-center text-brand underline" to={`/devices?q=${encodeURIComponent(d.device_id)}`}>장비 관리에서 보기</Link>
      </div>
    </div>
  )
}
