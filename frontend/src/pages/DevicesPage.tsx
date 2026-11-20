import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { KeyRound, Plus, X } from 'lucide-react'
import type { DeviceDetail, DeviceRow, Page } from '../api/types'
import { api, ApiError, qs } from '../api/client'
import { useApi } from '../lib/useApi'
import { formatGps, formatKst } from '../lib/format'
import { revealPanel, sideBySide } from '../lib/reveal'
import { PageHeader } from '../components/layout'
import { BatteryGauge, ConfirmDialog, DemoBadge, Dialog, EmptyState, ErrorNotice, LastSeenIndicator, LoadingSkeleton, btnPrimary, btnSecondary, inputCls, useToast } from '../components/ui'
import { DeviceIdSearch } from '../components/events'
import { Pagination } from '../components/events'

const SIZE = 20


export function DevicesPage() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const page = Number(params.get('page') ?? 0)
  const hideInactive = params.get('hide_inactive') === '1'
  const lowBattery = params.get('low_battery') === '1'                                   // dashboard card "배터리 주의 장비"
  const recentHours = Number(params.get('received_within_hours')) || 0                  // dashboard card "최근 24시간 수신 장비"
  // The URL is the single source of truth, so search, paging and the filters never drop each other.
  const go = (next: { q?: string; hideInactive?: boolean; lowBattery?: boolean; recentHours?: number; page?: number }) => {
    const nq = next.q ?? q, nh = next.hideInactive ?? hideInactive, nl = next.lowBattery ?? lowBattery, nr = next.recentHours ?? recentHours, np = next.page ?? 0
    setParams({ ...(nq ? { q: nq } : {}), ...(nh ? { hide_inactive: '1' } : {}), ...(nl ? { low_battery: '1' } : {}), ...(nr ? { received_within_hours: String(nr) } : {}), ...(np ? { page: String(np) } : {}) }, { replace: true })
  }
  const list = useApi<Page<DeviceRow>>(`/devices${qs({ q, page, size: SIZE, active: hideInactive ? 'true' : undefined, low_battery: lowBattery ? 'true' : undefined, received_within_hours: recentHours || undefined })}`)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const hasSimulated = list.data?.items.some((d) => d.simulated)
  const panelRef = useRef<HTMLElement>(null)

  // Stacked layout: the panel is far below the cards, so selecting one would look like nothing happened.
  // Bring the panel into view (and focus it for screen readers); closing it returns to the card.
  // The panel reserves its height up front (min-h on the aside): its content loads after this scroll starts, and a
  // page that is still short would clamp the scroll and leave the panel half below the fold.
  useEffect(() => {
    if (selectedId) revealPanel(panelRef.current)
  }, [selectedId])
  const closePanel = () => {
    const id = selectedId
    setSelectedId(null)
    if (id && !sideBySide()) {
      requestAnimationFrame(() => document.getElementById(`device-${id}`)?.scrollIntoView({ block: 'center' }))
    }
  }

  return (
    <>
      <PageHeader
        title="장비 관리"
        demo={hasSimulated}
        subtitle="‘최근 수신’은 서버가 마지막으로 데이터를 받은 시각입니다. 하트비트 규격이 합의되기 전까지 온라인/오프라인으로 표시하지 않습니다."
        actions={<button type="button" className={btnPrimary} onClick={() => setCreating(true)}><Plus size={16} aria-hidden="true" />장비 등록</button>}
      />
      <div className="mb-4 flex flex-wrap items-center gap-x-6 gap-y-2">
        <div className="w-full max-w-md"><DeviceIdSearch label="장비 검색" value={q} onSubmit={(v) => go({ q: v })} /></div>
        <label className="inline-flex min-h-11 cursor-pointer items-center gap-2 text-sm font-medium text-slate-700">
          <input key={String(hideInactive)} type="checkbox" defaultChecked={hideInactive} onChange={(e) => go({ hideInactive: e.target.checked })} className="size-4 accent-brand" data-testid="hide-inactive" />
          비활성 장비 숨기기
        </label>
      </div>
      {(lowBattery || recentHours > 0) && (
        <div className="mb-4 flex flex-wrap items-center gap-2" data-testid="device-filters" role="group" aria-label="적용 중인 필터">
          {lowBattery && <button type="button" onClick={() => go({ lowBattery: false })} aria-label="배터리 주의 필터 해제" className="inline-flex min-h-11 items-center gap-1 rounded-full border border-amber-300 bg-amber-50 px-3 text-sm font-medium text-amber-900">배터리 주의 장비 (마지막 보고 기준) <X size={14} aria-hidden="true" /></button>}
          {recentHours > 0 && <button type="button" onClick={() => go({ recentHours: 0 })} aria-label="최근 수신 필터 해제" className="inline-flex min-h-11 items-center gap-1 rounded-full border border-blue-300 bg-blue-50 px-3 text-sm font-medium text-blue-900">최근 {recentHours}시간 내 수신 (온라인 여부 아님) <X size={14} aria-hidden="true" /></button>}
        </div>
      )}
      <div className="grid gap-4 xl:grid-cols-[1fr_380px]">
        <section className="min-w-0 rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
          {list.error && <ErrorNotice message={list.error} onRetry={list.reload} />}
          {list.loading && !list.data && <LoadingSkeleton rows={6} />}
          {list.data && list.data.items.length === 0 && <EmptyState title={lowBattery ? '배터리 주의 장비가 없습니다' : recentHours > 0 ? `최근 ${recentHours}시간 안에 수신한 장비가 없습니다` : hideInactive ? '표시할 활성 장비가 없습니다' : '등록된 장비가 없습니다'} hint={hideInactive ? '‘비활성 장비 숨기기’를 해제하면 비활성 장비도 볼 수 있습니다.' : '‘장비 등록’으로 장비를 추가한 뒤 API 키를 발급하세요.'} />}
          {list.data && list.data.items.length > 0 && (
            <>
              <div className="hidden overflow-x-auto md:block">
                <table className="w-full whitespace-nowrap text-sm" data-testid="device-table">
                  <caption className="sr-only">장비 목록</caption>
                  <thead className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold text-slate-500">
                    <tr><th className="whitespace-nowrap px-2.5 py-2.5">장비 ID</th><th className="whitespace-nowrap px-2.5 py-2.5">구역</th><th className="whitespace-nowrap px-2.5 py-2.5">상태</th><th className="whitespace-nowrap px-2.5 py-2.5">최근 수신</th><th className="whitespace-nowrap px-2.5 py-2.5">배터리</th><th className="hidden whitespace-nowrap px-2.5 py-2.5 min-[1400px]:table-cell">마지막 위반 위치</th></tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {list.data.items.map((d) => (
                      <tr key={d.device_id} className={selectedId === d.device_id ? 'bg-blue-50' : 'hover:bg-blue-50/50'}>
                        <td className="whitespace-nowrap px-2.5 py-2.5"><button type="button" aria-pressed={selectedId === d.device_id} onClick={() => setSelectedId(d.device_id)} title={d.device_id} className="block min-h-8 max-w-[11rem] truncate text-left font-semibold text-brand hover:underline">{d.device_id}</button></td>
                        <td className="whitespace-nowrap px-2.5 py-2.5">{d.zone ?? '-'}</td>
                        <td className="whitespace-nowrap px-2.5 py-2.5">{d.active ? '사용' : <span className="font-medium text-slate-500">비활성</span>}</td>
                        <td className="whitespace-nowrap px-2.5 py-2.5"><LastSeenIndicator iso={d.last_received_at} /></td>
                        <td className="whitespace-nowrap px-2.5 py-2.5"><BatteryGauge level={d.last_battery_level} /></td>
                        <td className="hidden whitespace-nowrap px-2.5 py-2.5 tabular-nums min-[1400px]:table-cell">{d.last_known_gps ? formatGps(d.last_known_gps.latitude, d.last_known_gps.longitude) : <span className="text-slate-400">없음</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <ul className="space-y-2 md:hidden" aria-label="장비 카드 목록">
                {list.data.items.map((d) => (
                  <li key={d.device_id} id={`device-${d.device_id}`}>
                    <button type="button" aria-pressed={selectedId === d.device_id} onClick={() => setSelectedId(d.device_id)}
                      className={`w-full rounded-xl border p-3 text-left ${selectedId === d.device_id ? 'border-brand bg-blue-50 ring-1 ring-brand' : 'border-slate-200'}`}>
                      <div className="flex items-center justify-between"><span className="font-semibold text-brand">{d.device_id}</span><BatteryGauge level={d.last_battery_level} /></div>
                      <p className="mt-1 text-sm text-slate-500">{d.zone ?? '구역 미지정'}{!d.active && ' · 비활성'}</p>
                      <div className="mt-2"><LastSeenIndicator iso={d.last_received_at} /></div>
                    </button>
                  </li>
                ))}
              </ul>
              <Pagination page={list.data.page} size={list.data.size} total={list.data.total} onPage={(p) => go({ page: p })} />
            </>
          )}
        </section>
        <aside ref={panelRef} tabIndex={-1} aria-label="장비 상세" className={`scroll-mt-20 rounded-xl border border-slate-200 bg-white p-4 shadow-sm outline-none focus-visible:ring-2 focus-visible:ring-brand xl:sticky xl:top-20 xl:self-start ${selectedId ? 'min-h-[30rem] xl:min-h-0' : ''}`}>
          {selectedId ? <DeviceAdminPanel deviceId={selectedId} onChanged={list.reload} onClose={closePanel} /> : <EmptyState title="장비를 선택해 주세요" hint="API 키 상태와 사용 여부를 관리할 수 있습니다." />}
        </aside>
      </div>
      {creating && <CreateDeviceDialog onClose={() => setCreating(false)} onCreated={(id) => { setCreating(false); list.reload(); setSelectedId(id) }} />}
    </>
  )
}

function CreateDeviceDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const [id, setId] = useState('')
  const [alias, setAlias] = useState('')
  const [zone, setZone] = useState('')
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      await api('/devices', { method: 'POST', body: { device_id: id.trim(), alias: alias.trim() || null, zone: zone.trim() || null } })
      toast('장비를 등록했습니다. 이제 API 키를 발급하세요.')
      onCreated(id.trim())
    } catch (err) {
      setError(err instanceof ApiError ? (err.status === 409 ? '이미 등록된 장비 ID입니다.' : err.message) : '등록하지 못했습니다.')
    }
  }
  return (
    <Dialog title="장비 등록" onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>}
        <label className="block text-sm font-medium">장비 ID<input required value={id} onChange={(e) => setId(e.target.value)} pattern="[A-Za-z0-9._:\-]{1,64}" className={`${inputCls} mt-1`} placeholder="PAD-0051" /></label>
        <label className="block text-sm font-medium">별칭<input value={alias} onChange={(e) => setAlias(e.target.value)} maxLength={100} className={`${inputCls} mt-1`} /></label>
        <label className="block text-sm font-medium">설치 구역<input value={zone} onChange={(e) => setZone(e.target.value)} maxLength={100} className={`${inputCls} mt-1`} /></label>
        <div className="flex justify-end gap-2"><button type="button" className={btnSecondary} onClick={onClose}>취소</button><button type="submit" className={btnPrimary}>등록</button></div>
      </form>
    </Dialog>
  )
}

function DeviceAdminPanel({ deviceId, onChanged, onClose }: { deviceId: string; onChanged: () => void; onClose: () => void }) {
  const { data, error, loading, reload } = useApi<DeviceDetail>(`/devices/${encodeURIComponent(deviceId)}`)
  const toast = useToast()
  const [confirm, setConfirm] = useState<null | { kind: 'issue' } | { kind: 'revoke'; id: number } | { kind: 'active'; active: boolean }>(null)
  const [newKey, setNewKey] = useState<string | null>(null)

  if (error) return <ErrorNotice message={error} onRetry={reload} />
  if (loading || !data) return <LoadingSkeleton rows={6} />
  const d = data.device
  const activeKeys = data.credentials.filter((c) => c.active && !c.revoked_at)

  const run = async () => {
    const c = confirm
    setConfirm(null)
    if (!c) return
    try {
      if (c.kind === 'issue') {
        const r = await api<{ api_key: string }>(`/devices/${encodeURIComponent(deviceId)}/credentials`, { method: 'POST', body: { confirm: true } })
        setNewKey(r.api_key)
      } else if (c.kind === 'revoke') {
        await api(`/devices/${encodeURIComponent(deviceId)}/credentials/${c.id}/revoke`, { method: 'POST', body: { confirm: true } })
        toast('키를 폐기했습니다.')
      } else {
        await api(`/devices/${encodeURIComponent(deviceId)}/active`, { method: 'POST', body: { active: c.active } })
        toast(c.active ? '장비를 활성화했습니다.' : '장비를 비활성화했습니다. 이 장비의 전송은 거부됩니다.')
      }
      reload()
      onChanged()
    } catch (err) {
      toast(err instanceof ApiError ? err.message : '요청을 처리하지 못했습니다.', 'error')
    }
  }

  return (
    <div data-testid="device-admin-panel">
      <div className="flex items-start justify-between gap-2">
        <div><p className="text-lg font-bold">{d.device_id} {d.simulated && <DemoBadge className="ml-1 align-middle" />}</p><p className="text-sm text-slate-500">{d.alias ?? '별칭 없음'} · {d.zone ?? '구역 미지정'}</p></div>
        <button type="button" onClick={onClose} className={btnSecondary}>닫기</button>
      </div>
      <dl className="mt-3 space-y-2 text-sm">
        <div className="flex justify-between"><dt className="text-slate-500">상태</dt><dd className="font-medium">{d.active ? '사용 중' : '비활성'}</dd></div>
        <div className="flex justify-between"><dt className="text-slate-500">최근 수신</dt><dd className="tabular-nums">{formatKst(d.last_received_at)}</dd></div>
        <div className="flex justify-between"><dt className="text-slate-500">누적 이벤트</dt><dd>{d.event_count}건</dd></div>
        <div data-testid="admin-last-violation">
          <dt className="text-slate-500">마지막 위반 위치 <span className="text-xs">(과거 이벤트 지점, 실시간 위치 아님)</span></dt>
          <dd className="mt-1 tabular-nums">
            {d.last_known_gps
              ? <>{formatGps(d.last_known_gps.latitude, d.last_known_gps.longitude)}<br /><span className="text-xs text-slate-500">{formatKst(d.last_known_gps.event_time)} 기록</span></>
              : <span className="text-slate-400">GPS가 있는 이벤트 없음</span>}
          </dd>
        </div>
      </dl>

      <h3 className="mt-5 flex items-center gap-2 font-semibold"><KeyRound size={16} aria-hidden="true" />API 키</h3>
      <p className="mt-1 text-xs text-slate-500">키 원문은 발급 직후 한 번만 보입니다. 교체: 새 키 발급 → 기기에 반영 → 이전 키 폐기.</p>
      <ul className="mt-2 space-y-2" aria-label="키 목록">
        {data.credentials.length === 0 && <li className="text-sm text-slate-500">발급된 키가 없습니다.</li>}
        {data.credentials.map((c) => (
          <li key={c.credential_id} className="flex items-center justify-between gap-2 rounded-lg border border-slate-200 px-3 py-2 text-sm">
            <span><code className="font-mono">{c.key_prefix}…</code> <span className="text-xs text-slate-500">{c.revoked_at ? `폐기 ${formatKst(c.revoked_at)}` : `발급 ${formatKst(c.issued_at)}`}</span></span>
            {c.active && !c.revoked_at ? <button type="button" className="min-h-11 px-2 text-sm font-medium text-red-700 hover:underline" onClick={() => setConfirm({ kind: 'revoke', id: c.credential_id })}>폐기</button> : <span className="text-xs text-slate-400">사용 불가</span>}
          </li>
        ))}
      </ul>
      <div className="mt-4 flex flex-wrap gap-2">
        <button type="button" className={btnPrimary} onClick={() => setConfirm({ kind: 'issue' })}>새 키 발급</button>
        <button type="button" className={btnSecondary} onClick={() => setConfirm({ kind: 'active', active: !d.active })}>{d.active ? '비활성화' : '활성화'}</button>
      </div>
      {activeKeys.length === 0 && d.active && <p className="mt-3 text-xs text-amber-800">활성 키가 없어 이 장비는 데이터를 보낼 수 없습니다.</p>}

      {confirm?.kind === 'issue' && <ConfirmDialog title="API 키 발급" message="새 키를 발급합니다. 키 원문은 이 화면에서 한 번만 표시되며 다시 확인할 수 없습니다. 발급 기록은 감사 로그에 남습니다." confirmLabel="발급" onConfirm={run} onCancel={() => setConfirm(null)} />}
      {confirm?.kind === 'revoke' && <ConfirmDialog danger title="키 폐기" message="이 키로는 더 이상 전송할 수 없습니다. 기기에 새 키가 반영되었는지 확인하세요." confirmLabel="폐기" onConfirm={run} onCancel={() => setConfirm(null)} />}
      {confirm?.kind === 'active' && <ConfirmDialog danger={!confirm.active} title={confirm.active ? '장비 활성화' : '장비 비활성화'} message={confirm.active ? '이 장비의 데이터 수신을 다시 허용합니다.' : '비활성화하면 이 장비의 전송이 거부됩니다(403).'} confirmLabel={confirm.active ? '활성화' : '비활성화'} onConfirm={run} onCancel={() => setConfirm(null)} />}
      {newKey && (
        <Dialog title="새 API 키" onClose={() => setNewKey(null)}>
          <p className="text-sm text-slate-700">지금 안전한 곳에 복사해 두세요. 닫으면 다시 볼 수 없습니다.</p>
          <input readOnly value={newKey} onFocus={(e) => e.currentTarget.select()} aria-label="발급된 API 키" data-testid="new-api-key" className={`${inputCls} mt-3 font-mono`} />
          <div className="mt-4 flex justify-end gap-2">
            <button type="button" className={btnSecondary} onClick={() => navigator.clipboard?.writeText(newKey).then(() => toast('복사했습니다.'))}>복사</button>
            <button type="button" className={btnPrimary} onClick={() => setNewKey(null)}>확인했습니다</button>
          </div>
        </Dialog>
      )}
    </div>
  )
}
