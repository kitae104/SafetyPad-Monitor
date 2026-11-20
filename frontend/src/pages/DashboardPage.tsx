import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import type { EventRow, MapEventsResponse, Page, Summary } from '../api/types'
import { eventsQuery } from '../api/client'
import { useApi } from '../lib/useApi'
import { revealPanel, sideBySide } from '../lib/reveal'
import { DemoBanner, PageHeader } from '../components/layout'
import { EmptyState, ErrorNotice, LoadingSkeleton, SummaryMetricCard } from '../components/ui'
import { BarList, ViolationTrendChart } from '../components/charts'
import { CSVExportButton, DeviceIdSearch, EventDetailDialog, EventList, EventTable } from '../components/events'
import { ViolationMap } from '../components/map/ViolationMap'
import { MapLegend } from '../components/map/MapInfoCard'
import { groupByDevice } from '../lib/mapSummary'
import { DeviceDetailPanel } from '../components/DeviceDetailPanel'

const LG = 1024                                       // the map and the selected-device panel sit side by side from `lg`

export function DashboardPage() {
  const nav = useNavigate()
  const summary = useApi<Summary>('/summary')
  const recent = useApi<Page<EventRow>>(`/events${eventsQuery({ size: 10 })}`)
  const mapEvents = useApi<MapEventsResponse>('/map/events')
  const [selected, setSelected] = useState<EventRow | null>(null)
  const [deviceId, setDeviceId] = useState<string | null>(null)
  const panelRef = useRef<HTMLElement>(null)
  const mapRef = useRef<HTMLElement>(null)
  const revealOnSelect = useRef(false)

  // Tapping a marker on a stacked layout: the panel sits under the map, so bring it into view.
  useEffect(() => {
    if (deviceId && revealOnSelect.current) {
      revealOnSelect.current = false
      revealPanel(panelRef.current, LG)
    }
  }, [deviceId])
  const onMarker = (id: string | null) => {                // null = the map deselected (empty-area click)
    if (id === deviceId) {
      if (id) revealPanel(panelRef.current, LG)            // same device again: no state change, so no effect run
    } else if (id) {
      revealOnSelect.current = true
    }
    setDeviceId(id)
  }
  const clearSelection = () => {
    setDeviceId(null)
    if (!sideBySide(LG)) requestAnimationFrame(() => mapRef.current?.scrollIntoView({ block: 'start' }))
  }

  const s = summary.data
  // The server decides "last 7 days" in Asia/Seoul, so the export always matches the numbers on screen.
  const exportFilters = s ? { from: s.period.from, to: s.period.to } : {}

  return (
    <>
      <PageHeader
        title="대시보드"
        demo={s?.simulated_data_present}
        subtitle={s ? `기간 ${s.period.from} ~ ${s.period.to} · 한국 표준시(Asia/Seoul)` : '불러오는 중'}
        actions={
          <>
            <div className="w-full sm:w-64">
              <DeviceIdSearch value="" onSubmit={(v) => nav(`/events${v ? `?device_id=${encodeURIComponent(v)}` : ''}`)} />
            </div>
            <CSVExportButton filters={exportFilters} label="최근 7일 CSV" />
          </>
        }
      />
      {s?.simulated_data_present && <DemoBanner />}
      {summary.error && <ErrorNotice message={summary.error} onRetry={summary.reload} />}

      <div className="grid grid-cols-2 gap-3 xl:grid-cols-4" data-testid="kpi-grid">
        <SummaryMetricCard testId="kpi-devices" label="등록 장비 수" value={s?.registered_devices ?? '–'} hint="활성 등록 기준" to="/devices?hide_inactive=1" cta="장비 목록 보기" />
        <SummaryMetricCard testId="kpi-today" label="오늘 위반 건수" value={s?.violations_today ?? '–'} hint="한국 시간 0시 기준" to={s ? `/events?from=${s.period.to}&to=${s.period.to}` : undefined} cta="오늘 이벤트 보기" />
        <SummaryMetricCard testId="kpi-received" label="최근 24시간 수신 장비" value={s?.devices_received_last_24h ?? '–'} hint="수신 기록 기준 · 온라인 여부 아님" to="/devices?received_within_hours=24" cta="해당 장비 보기" />
        <SummaryMetricCard testId="kpi-battery" label="배터리 주의 장비" value={s?.low_battery_devices ?? '–'} tone="warn" hint={s ? `${s.low_battery_threshold}% 이하 (마지막 보고 기준)` : undefined} to="/devices?low_battery=1" cta="해당 장비 보기" />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-3">
        <section ref={mapRef} className={`min-w-0 scroll-mt-20 rounded-xl border border-slate-200 bg-white p-4 shadow-sm ${deviceId ? 'lg:col-span-2' : 'lg:col-span-3'}`} aria-labelledby="map-title">
          <div className="mb-1 flex flex-wrap items-baseline justify-between gap-x-3">
            <h2 id="map-title" className="font-semibold text-slate-900">위반 위치 지도 <Link to="/map" className="ml-2 text-sm font-medium text-brand hover:underline">크게 보기·위반 목록</Link></h2>
            {mapEvents.data && (
              <p className="text-xs text-slate-600" data-testid="dash-map-summary">위반 {mapEvents.data.points.length.toLocaleString()}건 · 장비 {groupByDevice(mapEvents.data.points).length}대{mapEvents.data.missing_gps_count > 0 && ` · GPS 없음 ${mapEvents.data.missing_gps_count.toLocaleString()}건(지도 제외)`}</p>
            )}
          </div>
          <MapLegend />
          {mapEvents.loading && !mapEvents.data ? (
            <LoadingSkeleton rows={4} />
          ) : (
            <ViolationMap points={mapEvents.data?.points ?? []} selectedDeviceId={deviceId} onSelectDevice={onMarker} height={deviceId ? 400 : 520} />
          )}
        </section>
        {/* the map takes the whole row until a device is chosen; only then does the detail panel appear */}
        {deviceId && (
          <section ref={panelRef} tabIndex={-1} className="min-h-[24rem] min-w-0 scroll-mt-20 rounded-xl border border-slate-200 bg-white p-4 shadow-sm outline-none focus-visible:ring-2 focus-visible:ring-brand lg:min-h-0" aria-labelledby="dev-title">
            <h2 id="dev-title" className="mb-3 font-semibold text-slate-900">선택 장비</h2>
            <DeviceDetailPanel deviceId={deviceId} onClear={clearSelection} />
          </section>
        )}
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-3">
        <section className="min-w-0 rounded-xl border border-slate-200 bg-white p-4 shadow-sm lg:col-span-2" aria-labelledby="trend-title">
          <h2 id="trend-title" className="mb-3 font-semibold text-slate-900">최근 7일 위반 추이</h2>
          <p className="-mt-2 mb-2 text-xs text-slate-500">날짜 점을 누르면 그날의 위반 이벤트를 볼 수 있습니다.</p>
          {s ? <ViolationTrendChart data={s.daily_trend} onSelectDay={(d) => nav(`/events?from=${d}&to=${d}`)} /> : <LoadingSkeleton />}
        </section>
        <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm" aria-labelledby="top-title">
          <h2 id="top-title" className="mb-3 font-semibold text-slate-900">장비별 위반 상위 (7일)</h2>
          {s ? (
            <BarList rows={s.top_devices.map((d) => ({ label: d.device_id, sub: d.alias ?? undefined, value: d.count }))} empty="기간 내 위반 기록이 없습니다"
              onSelect={(r) => nav(`/events?device_id=${encodeURIComponent(r.label)}&from=${s.period.from}&to=${s.period.to}`)} selectHint={() => '이 장비의 7일간 이벤트 보기'} />
          ) : (
            <LoadingSkeleton />
          )}
        </section>
      </div>

      <section className="mt-4 rounded-xl border border-slate-200 bg-white p-4 shadow-sm" aria-labelledby="recent-title">
        <div className="mb-3 flex items-center justify-between">
          <h2 id="recent-title" className="font-semibold text-slate-900">최근 위반 이벤트</h2>
          <button type="button" onClick={() => nav('/events')} className="min-h-11 px-2 text-sm font-medium text-brand hover:underline">전체 보기</button>
        </div>
        {recent.error && <ErrorNotice message={recent.error} onRetry={recent.reload} />}
        {recent.loading && !recent.data && <LoadingSkeleton rows={5} />}
        {recent.data && recent.data.items.length === 0 && <EmptyState title="아직 수신된 위반 이벤트가 없습니다" hint="기기가 HTTPS POST로 이벤트를 보내면 여기에 표시됩니다." />}
        {recent.data && recent.data.items.length > 0 && (
          <>
            <EventTable rows={recent.data.items} onSelect={setSelected} compact />
            <EventList rows={recent.data.items} onSelect={setSelected} />
          </>
        )}
      </section>
      {selected && <EventDetailDialog e={selected} onClose={() => setSelected(null)} onShowOnMap={(e) => { setDeviceId(e.device_id); setSelected(null) }} />}
    </>
  )
}
