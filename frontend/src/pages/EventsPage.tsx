import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import type { EventFilters, EventRow, Page } from '../api/types'
import { eventsQuery } from '../api/client'
import { useApi } from '../lib/useApi'
import { PageHeader } from '../components/layout'
import { DemoBadge, EmptyState, ErrorNotice, LoadingSkeleton, inputCls } from '../components/ui'
import { CSVExportButton, DateRangeFilter, DeviceIdSearch, EventDetailDialog, EventList, EventTable, Pagination } from '../components/events'

const PAGE_SIZE = 20

export function EventsPage() {
  const [params, setParams] = useSearchParams()
  const nav = useNavigate()
  const [selected, setSelected] = useState<EventRow | null>(null)

  const filters: EventFilters = useMemo(
    () => ({
      from: params.get('from') ?? '',
      to: params.get('to') ?? '',
      device_id: params.get('device_id') ?? '',
      min_lag_seconds: params.get('min_lag_seconds') ?? '',
      has_gps: params.get('has_gps') ?? '',
      sort: params.get('sort') ?? 'event_time',
      dir: params.get('dir') ?? 'desc',
      page: Number(params.get('page') ?? 0),
      size: PAGE_SIZE,
    }),
    [params],
  )
  const { data, error, loading, reload } = useApi<Page<EventRow>>(`/events${eventsQuery(filters)}`)

  const update = (patch: Record<string, string | number | undefined>, resetPage = true) => {
    const next = new URLSearchParams(params)
    for (const [k, v] of Object.entries(patch)) {
      if (v === undefined || v === '') next.delete(k)
      else next.set(k, String(v))
    }
    if (resetPage) next.delete('page')
    setParams(next, { replace: true })
  }
  const onSort = (col: string) => update({ sort: col, dir: filters.sort === col && filters.dir === 'desc' ? 'asc' : 'desc' })
  const anyFilter = !!(filters.from || filters.to || filters.device_id || filters.min_lag_seconds || filters.has_gps)
  const hasSimulated = data?.items.some((e) => e.simulated)

  return (
    <>
      <PageHeader title="위반 이벤트" demo={hasSimulated} subtitle="시각은 모두 한국 표준시(Asia/Seoul)입니다. 최초 수신 시각은 재전송되어도 바뀌지 않습니다." actions={<CSVExportButton filters={filters} />} />

      <section aria-label="검색 조건" className="mb-4 grid gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm md:grid-cols-2 xl:grid-cols-4">
        <DateRangeFilter from={filters.from ?? ''} to={filters.to ?? ''} onChange={(f, t) => update({ from: f, to: t })} />
        <div className="flex flex-col justify-end">
          <DeviceIdSearch value={filters.device_id ?? ''} onSubmit={(v) => update({ device_id: v })} />
        </div>
        <label className="text-xs font-medium text-slate-600">
          수신 지연
          <select value={filters.min_lag_seconds} onChange={(e) => update({ min_lag_seconds: e.target.value })} className={`${inputCls} mt-1`}>
            <option value="">전체</option>
            <option value="60">1분 이상</option>
            <option value="600">10분 이상</option>
            <option value="3600">1시간 이상</option>
          </select>
        </label>
        <label className="text-xs font-medium text-slate-600">
          GPS
          <select value={filters.has_gps} onChange={(e) => update({ has_gps: e.target.value })} className={`${inputCls} mt-1`}>
            <option value="">전체</option>
            <option value="true">위치 있음</option>
            <option value="false">위치 없음</option>
          </select>
        </label>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        {error && <ErrorNotice message={error} onRetry={reload} />}
        {loading && !data && <LoadingSkeleton rows={6} />}
        {data && data.items.length === 0 && (
          <EmptyState
            title={anyFilter ? '조건에 맞는 이벤트가 없습니다' : '아직 수신된 위반 이벤트가 없습니다'}
            hint={anyFilter ? '기간이나 장비 ID 조건을 넓혀 보세요.' : undefined}
          />
        )}
        {data && data.items.length > 0 && (
          <>
            <div className="mb-2 flex items-center gap-2 text-sm text-slate-600">
              <span data-testid="result-count">{data.total.toLocaleString()}건</span>
              {hasSimulated && <DemoBadge />}
            </div>
            <EventTable rows={data.items} onSelect={setSelected} sort={filters.sort} dir={filters.dir} onSort={onSort} />
            <EventList rows={data.items} onSelect={setSelected} />
            <Pagination page={data.page} size={data.size} total={data.total} onPage={(p) => update({ page: p || undefined }, false)} />
          </>
        )}
      </section>
      {selected && <EventDetailDialog e={selected} onClose={() => setSelected(null)} onShowOnMap={(e) => nav(`/map?device_id=${encodeURIComponent(e.device_id)}`)} />}
    </>
  )
}
