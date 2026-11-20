import { useState, type FormEvent } from 'react'
import { ArrowDown, ArrowUp, Download, Search } from 'lucide-react'
import type { EventFilters, EventRow } from '../api/types'
import { csvExportUrl } from '../api/client'
import { formatGps, formatKst, formatLag } from '../lib/format'
import { BatteryGauge, btnSecondary, Dialog, inputCls } from './ui'

export function CSVExportButton({ filters, label = 'CSV 내보내기' }: { filters: EventFilters; label?: string }) {
  return (
    <a href={csvExportUrl(filters)} download className={btnSecondary} data-testid="csv-export">
      <Download size={16} aria-hidden="true" />
      {label}
    </a>
  )
}

export function DeviceIdSearch({ value, onSubmit, label = '장비 ID 검색' }: { value: string; onSubmit: (v: string) => void; label?: string }) {
  const [text, setText] = useState(value)
  const submit = (e: FormEvent) => {
    e.preventDefault()
    onSubmit(text.trim())
  }
  return (
    <form onSubmit={submit} role="search" className="flex gap-2">
      <label className="relative block min-w-0 flex-1">
        <span className="sr-only">{label}</span>
        <Search size={16} aria-hidden="true" className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
        <input value={text} onChange={(e) => setText(e.target.value)} placeholder="예: PAD-0001" className={`${inputCls} pl-9`} maxLength={64} />
      </label>
      <button type="submit" className={btnSecondary}>검색</button>
    </form>
  )
}

export function DateRangeFilter({ from, to, onChange }: { from: string; to: string; onChange: (from: string, to: string) => void }) {
  return (
    <fieldset className="flex flex-wrap items-end gap-2">
      <legend className="sr-only">기간 (한국 표준시)</legend>
      <label className="text-xs font-medium text-slate-600">
        시작일
        <input type="date" value={from} max={to || undefined} onChange={(e) => onChange(e.target.value, to)} className={`${inputCls} mt-1`} />
      </label>
      <label className="text-xs font-medium text-slate-600">
        종료일
        <input type="date" value={to} min={from || undefined} onChange={(e) => onChange(from, e.target.value)} className={`${inputCls} mt-1`} />
      </label>
    </fieldset>
  )
}

function GpsCell({ e }: { e: EventRow }) {
  return e.latitude == null ? <span className="text-slate-400">위치 없음</span> : <span className="tabular-nums">{formatGps(e.latitude, e.longitude)}</span>
}

function LagCell({ e }: { e: EventRow }) {
  return (
    <span className={e.clock_skew_suspected ? 'font-medium text-amber-700' : e.lag_seconds >= 600 ? 'text-amber-700' : ''}>
      {formatLag(e.lag_seconds)}
    </span>
  )
}

export function EventTable({
  rows,
  onSelect,
  sort,
  dir,
  onSort,
  compact = false,
}: {
  rows: EventRow[]
  onSelect: (e: EventRow) => void
  sort?: string
  dir?: string
  onSort?: (col: string) => void
  compact?: boolean
}) {
  const th = (col: string | null, text: string) => {
    const active = col && sort === col
    return (
      <th scope="col" aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : undefined} className="whitespace-nowrap px-3 py-2.5 text-left text-xs font-semibold text-slate-500">
        {col && onSort ? (
          <button type="button" onClick={() => onSort(col)} className="inline-flex min-h-8 items-center gap-1 hover:text-slate-900">
            {text}
            {active && (dir === 'asc' ? <ArrowUp size={12} aria-hidden="true" /> : <ArrowDown size={12} aria-hidden="true" />)}
          </button>
        ) : (
          text
        )}
      </th>
    )
  }
  return (
    <div className="hidden overflow-x-auto md:block">
      <table className="w-full text-sm" data-testid="event-table">
        <caption className="sr-only">위반 이벤트 목록 (시각은 한국 표준시)</caption>
        <thead className="border-b border-slate-200 bg-slate-50">
          <tr>
            {th('device_id', '장비 ID')}
            {th('event_time', '발생 시각')}
            {th('received_at', '최초 수신')}
            {!compact && th(null, '지연')}
            {th(null, 'GPS')}
            {th(null, '배터리')}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {rows.map((e) => (
            <tr key={e.id} className="hover:bg-blue-50/50">
              <td className="px-3 py-2.5">
                <button type="button" onClick={() => onSelect(e)} className="min-h-8 font-semibold text-brand underline-offset-2 hover:underline" aria-label={`${e.device_id} 이벤트 상세 보기`}>
                  {e.device_id}
                </button>
              </td>
              <td className="whitespace-nowrap px-3 py-2.5 tabular-nums">{formatKst(e.event_time)}</td>
              <td className="whitespace-nowrap px-3 py-2.5 tabular-nums">{formatKst(e.received_at)}</td>
              {!compact && <td className="px-3 py-2.5"><LagCell e={e} /></td>}
              <td className="px-3 py-2.5"><GpsCell e={e} /></td>
              <td className="px-3 py-2.5"><BatteryGauge level={e.battery_level} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function EventMobileCard({ e, onSelect }: { e: EventRow; onSelect: (e: EventRow) => void }) {
  return (
    <li>
      <button type="button" onClick={() => onSelect(e)} className="w-full rounded-xl border border-slate-200 bg-white p-3 text-left shadow-sm active:bg-slate-50" data-testid="event-card">
        <div className="flex items-center justify-between gap-2">
          <span className="font-semibold text-brand">{e.device_id}</span>
          <BatteryGauge level={e.battery_level} />
        </div>
        <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-slate-500">발생</dt><dd className="tabular-nums">{formatKst(e.event_time)}</dd>
          <dt className="text-slate-500">수신</dt><dd className="tabular-nums">{formatKst(e.received_at)}</dd>
          <dt className="text-slate-500">지연</dt><dd><LagCell e={e} /></dd>
          <dt className="text-slate-500">GPS</dt><dd><GpsCell e={e} /></dd>
        </dl>
      </button>
    </li>
  )
}

export function EventList({ rows, onSelect }: { rows: EventRow[]; onSelect: (e: EventRow) => void }) {
  return (
    <ul className="space-y-2 md:hidden" aria-label="위반 이벤트 카드 목록">
      {rows.map((e) => <EventMobileCard key={e.id} e={e} onSelect={onSelect} />)}
    </ul>
  )
}

export function EventDetailDialog({ e, onClose, onShowOnMap }: { e: EventRow; onClose: () => void; onShowOnMap?: (e: EventRow) => void }) {
  const rows: [string, React.ReactNode][] = [
    ['이벤트 ID', e.event_id],
    ['장비', `${e.device_id}${e.alias ? ` (${e.alias})` : ''}`],
    ['유형', e.event_type === 'MULTI_RIDE' ? '다인 승차 (MULTI_RIDE)' : e.event_type],
    ['발생 시각 (기기 기록)', formatKst(e.event_time)],
    ['최초 수신 (서버 기록)', formatKst(e.received_at)],
    ['수신 지연', <LagCell key="lag" e={e} />],
    ['GPS', <GpsCell key="gps" e={e} />],
    ['배터리', <BatteryGauge key="bat" level={e.battery_level} />],
    ['데이터 출처', e.simulated ? '가상 데이터' : '실제 수신'],
  ]
  return (
    <Dialog title="이벤트 상세" onClose={onClose} wide>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
        {rows.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-slate-500">{k}</dt>
            <dd className="break-all font-medium text-slate-900">{v}</dd>
          </div>
        ))}
      </dl>
      {e.clock_skew_suspected && (
        <p className="mt-3 rounded-md bg-amber-50 p-2 text-xs text-amber-900">수신 시각이 발생 시각보다 이릅니다. 기기 시계(RTC) 오차가 의심됩니다.</p>
      )}
      {e.latitude == null && <p className="mt-3 text-xs text-slate-500">GPS가 없는 이벤트는 목록에는 남지만 지도에는 표시되지 않습니다.</p>}
      {e.latitude != null && onShowOnMap && (
        <button type="button" onClick={() => onShowOnMap(e)} className={`${btnSecondary} mt-4`}>지도에서 보기</button>
      )}
    </Dialog>
  )
}

export function Pagination({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size))
  return (
    <nav aria-label="페이지 이동" className="mt-4 flex items-center justify-between gap-2 text-sm">
      <span className="text-slate-600" data-testid="pagination-info">총 {total.toLocaleString()}건 · {page + 1}/{pages} 페이지</span>
      <span className="flex gap-2">
        <button type="button" disabled={page <= 0} onClick={() => onPage(page - 1)} className={btnSecondary}>이전</button>
        <button type="button" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)} className={btnSecondary}>다음</button>
      </span>
    </nav>
  )
}
