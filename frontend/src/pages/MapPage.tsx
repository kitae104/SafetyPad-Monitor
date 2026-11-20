import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import type { DeviceRow, MapEventsResponse, Page, RouteResponse } from '../api/types'
import { qs } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useApi } from '../lib/useApi'
import { revealPanel, sideBySide } from '../lib/reveal'
import { formatKst } from '../lib/format'
import { PageHeader } from '../components/layout'
import { ErrorNotice, LoadingSkeleton, inputCls, useToast, btnSecondary } from '../components/ui'
import { DateRangeFilter } from '../components/events'
import { ViolationMap } from '../components/map/ViolationMap'
import { MapLegend } from '../components/map/MapInfoCard'
import { ViolationListPanel } from '../components/map/ViolationListPanel'
import { groupByDevice, seoulToday, shiftDate } from '../lib/mapSummary'
import { DeviceDetailPanel } from '../components/DeviceDetailPanel'
import { api } from '../api/client'

export function MapPage() {
  const { config } = useAuth()
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  const selected = params.get('device_id')
  const routeOn = params.get('route') === '1'
  const routeDay = params.get('day') ?? ''
  const [search, setSearch] = useState('')
  const panelRef = useRef<HTMLElement>(null)
  const mapRef = useRef<HTMLElement>(null)
  const revealOnSelect = useRef(false)
  const [focus, setFocus] = useState<{ device: string | null; id: number } | null>(null)
  const focusPointId = focus && focus.device === selected ? focus.id : null   // a focus only applies to the device it was made for

  // Tapping a marker on a stacked layout: the detail panel is far below the map, so bring it into view.
  // Only marker taps do this (not opening a link that already carries ?device_id=, not the search box).
  useEffect(() => {
    if (selected && revealOnSelect.current) {
      revealOnSelect.current = false
      revealPanel(panelRef.current)
    }
  }, [selected])
  const onMarker = (id: string | null) => {                  // null = the map deselected (empty-area click)
    if (id === selected) {
      if (id) revealPanel(panelRef.current)                  // same device again: no URL change, so no effect run
    } else if (id) {
      revealOnSelect.current = true
    }
    set({ device_id: id, route: null, day: null })
  }
  const pickFromList = (id: string | null) => set({ device_id: id, route: null, day: null })
  const focusFromList = (id: number) => {
    setFocus({ device: selected, id })
    if (!sideBySide()) requestAnimationFrame(() => mapRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }))   // the list sits below the map on small screens
  }
  const today = seoulToday()
  const presets = [
    { label: '오늘', from: today, to: today },
    { label: '최근 7일', from: shiftDate(today, -6), to: today },
    { label: '최근 30일', from: shiftDate(today, -29), to: today },
    { label: '전체 기간', from: '', to: '' },
  ]
  const clearSelection = () => {
    set({ device_id: null, route: null, day: null })
    if (!sideBySide()) requestAnimationFrame(() => mapRef.current?.scrollIntoView({ block: 'start' }))
  }

  const set = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v)
      else next.delete(k)
    }
    setParams(next, { replace: true })
  }

  const events = useApi<MapEventsResponse>(`/map/events${qs({ from, to })}`)
  const routeAllowed = !!config?.route_enabled && !!selected && routeOn
  const route = useApi<RouteResponse>(routeAllowed ? `/devices/${encodeURIComponent(selected!)}/route${qs({ from: routeDay, to: routeDay })}` : null)

  const findDevice = async (e: React.FormEvent) => {
    e.preventDefault()
    const q = search.trim()
    if (!q) return set({ device_id: null, route: null, day: null })
    try {
      const r = await api<Page<DeviceRow>>(`/devices${qs({ q, size: 5 })}`)
      const exact = r.items.find((d) => d.device_id.toLowerCase() === q.toLowerCase()) ?? (r.items.length === 1 ? r.items[0] : null)
      if (exact) set({ device_id: exact.device_id, route: null, day: null })
      else toast(r.items.length ? '장비 ID를 더 정확히 입력해 주세요.' : '일치하는 장비가 없습니다.', 'error')
    } catch {
      toast('장비를 검색하지 못했습니다.', 'error')
    }
  }

  const d = events.data
  const selectedCount = d && selected ? d.points.filter((p) => p.device_id === selected).length : 0
  const hasSimulated = d?.points.some((p) => p.simulated)
  const r = route.data

  return (
    <>
      <PageHeader title="위치·경로" demo={hasSimulated} subtitle="기본 화면은 위반이 발생한 지점만 보여 줍니다. 장비의 실시간 위치가 아닙니다." />

      <section aria-label="지도 조건" className="mb-4 grid gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm md:grid-cols-[auto_1fr_auto] md:items-end">
        <div className="space-y-2">
          <DateRangeFilter from={from} to={to} onChange={(f, t) => set({ from: f || null, to: t || null })} />
          <div role="group" aria-label="기간 빠른 선택" className="flex flex-wrap gap-1.5">
            {presets.map((x) => {
              const on = x.from === from && x.to === to
              return (
                <button key={x.label} type="button" aria-pressed={on} onClick={() => set({ from: x.from || null, to: x.to || null })}
                  className={`min-h-11 rounded-full border px-3 text-sm font-medium focus-visible:outline-2 focus-visible:outline-brand ${on ? 'border-brand bg-blue-50 text-blue-900' : 'border-slate-300 bg-white text-slate-700 hover:bg-slate-50'}`}>{x.label}</button>
              )
            })}
          </div>
        </div>
        <form onSubmit={findDevice} role="search" className="flex gap-2">
          <label className="block min-w-0 flex-1 text-xs font-medium text-slate-600">
            장비 선택 (ID)
            <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={selected ?? '예: PAD-0001'} maxLength={64} className={`${inputCls} mt-1`} />
          </label>
          <button type="submit" className={`${btnSecondary} self-end`}>선택</button>
        </form>
        <fieldset className="text-sm" disabled={!config?.route_enabled || !selected}>
          <legend className="sr-only">이동 경로</legend>
          <label className="flex min-h-11 items-center gap-2">
            <input type="checkbox" checked={routeOn && !!config?.route_enabled} onChange={(e) => set({ route: e.target.checked ? '1' : null, day: null })} className="size-5" data-testid="route-toggle" />
            이동 경로 보기
          </label>
        </fieldset>
      </section>

      {!config?.route_enabled && (
        <p role="note" data-testid="route-disabled-note" className="mb-4 rounded-lg border border-slate-200 bg-slate-50 px-4 py-2 text-sm text-slate-600">
          이동 경로는 비활성화되어 있습니다. 기기의 주기적 GPS 전송 규격이 승인되기 전에는 위반 위치만 표시합니다.
        </p>
      )}
      {config?.route_enabled && !selected && (
        <p role="note" className="mb-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-2 text-sm text-blue-900">장비를 선택하면 해당 장비의 경로를 볼 수 있습니다. (전체 장비의 경로는 표시하지 않습니다)</p>
      )}

      <div className="grid gap-4 xl:grid-cols-[1fr_340px]">
        <section ref={mapRef} className="min-w-0 scroll-mt-20 rounded-xl border border-slate-200 bg-white p-4 shadow-sm" aria-labelledby="map-h">
          <h2 id="map-h" className="mb-1 font-semibold">위반 위치 지도</h2>
          <MapLegend route={routeAllowed} />
          {events.error && <ErrorNotice message={events.error} onRetry={events.reload} />}
          {events.loading && !d ? <LoadingSkeleton rows={6} /> : (
            <ViolationMap points={d?.points ?? []} selectedDeviceId={selected} onSelectDevice={onMarker} route={routeAllowed ? r : null} height={460} focusPointId={focusPointId} />
          )}
          {d && (
            <p className="mt-3 text-xs text-slate-600" data-testid="map-summary">
              표시 중: 위반 이벤트 {d.points.length.toLocaleString()}건 · 장비 {groupByDevice(d.points).length}대{selected && ` (선택 장비 ${selectedCount}건 강조)`}
              {d.truncated && ' · 최대 표시 건수를 넘어 일부만 보입니다'}
            </p>
          )}
          {d && d.missing_gps_count > 0 && (
            <p className="mt-1 text-xs text-slate-600" data-testid="missing-gps">
              GPS가 없는 이벤트 {d.missing_gps_count.toLocaleString()}건은 지도에 표시하지 않습니다.{' '}
              <Link className="font-medium text-brand underline" to={`/events?has_gps=false${selected ? `&device_id=${encodeURIComponent(selected)}` : ''}${from ? `&from=${from}` : ''}${to ? `&to=${to}` : ''}`}>목록에서 보기</Link>
            </p>
          )}

          {routeAllowed && (
            <div className="mt-4 rounded-lg border border-cyan-200 bg-cyan-50 p-3 text-sm" data-testid="route-info">
              {route.loading && !r && <LoadingSkeleton rows={2} />}
              {route.error && <ErrorNotice message={route.error} onRetry={route.reload} />}
              {r && (
                <>
                  <div className="flex flex-wrap items-center gap-3">
                    <span className="font-semibold text-cyan-900">{r.device_id} 정기 위치 경로</span>
                    {r.days_available.length > 0 && (
                      <label className="flex items-center gap-2 text-xs">날짜
                        <select value={r.range?.from ?? ''} onChange={(e) => set({ day: e.target.value })} className={`${inputCls} !min-h-9 !w-auto`}>
                          {r.days_available.map((day) => <option key={day} value={day}>{day}</option>)}
                        </select>
                      </label>
                    )}
                  </div>
                  {r.sufficient ? (
                    <p className="mt-2 text-cyan-900">수집 지점 {r.points.length}개 · 간격이 큰 구간 {r.gaps}곳 (기준 {r.max_gap_minutes}분 초과) · 마지막 수집 {formatKst(r.last_ping_at)}</p>
                  ) : (
                    <p className="mt-2 text-slate-700" data-testid="route-insufficient">경로를 그릴 만큼의 GPS 수집 지점이 없습니다 (현재 {r.points.length}개, 최소 2개 필요). 위반 위치만으로는 경로를 만들지 않습니다.</p>
                  )}
                  <p className="mt-2 text-xs text-cyan-900" data-testid="route-disclaimer">{r.disclaimer}</p>
                  <p className="mt-1 text-xs text-amber-800">데모 전용 기능입니다. 실제 기기의 주기 GPS 전송은 아직 승인되지 않았습니다.</p>
                </>
              )}
            </div>
          )}
        </section>
        <aside ref={panelRef} tabIndex={-1} className={`scroll-mt-20 rounded-xl border border-slate-200 bg-white p-4 shadow-sm outline-none focus-visible:ring-2 focus-visible:ring-brand xl:self-start ${selected ? 'min-h-[24rem] xl:min-h-0' : ''}`} aria-labelledby="sel-h">
          <h2 id="sel-h" className="mb-3 font-semibold">선택 장비</h2>
          <DeviceDetailPanel deviceId={selected} onClear={clearSelection} />
          <h2 id="list-h" className="mb-2 mt-5 border-t border-slate-200 pt-4 font-semibold">위반 목록</h2>
          {events.loading && !d ? <LoadingSkeleton rows={4} /> : <ViolationListPanel points={d?.points ?? []} selectedDeviceId={selected} focusPointId={focusPointId} onSelectDevice={pickFromList} onFocusPoint={focusFromList} from={from} to={to} />}
        </aside>
      </div>
    </>
  )
}
