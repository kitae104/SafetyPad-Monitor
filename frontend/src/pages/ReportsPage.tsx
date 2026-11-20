import { useNavigate } from 'react-router-dom'
import type { Summary } from '../api/types'
import { useApi } from '../lib/useApi'
import { LAG_BUCKET_LABELS, LAG_BUCKET_ORDER } from '../lib/format'
import { PageHeader } from '../components/layout'
import { ErrorNotice, LoadingSkeleton } from '../components/ui'
import { BarList, ViolationTrendChart } from '../components/charts'
import { CSVExportButton } from '../components/events'

export function ReportsPage() {
  const { data: s, error, reload } = useApi<Summary>('/summary')
  const nav = useNavigate()
  const range = s ? { from: s.period.from, to: s.period.to } : {}
  const lag = s ? LAG_BUCKET_ORDER.filter((k) => s.lag_distribution[k]).map((k) => ({ label: LAG_BUCKET_LABELS[k], value: s.lag_distribution[k] })) : []
  return (
    <>
      <PageHeader title="통계 리포트" demo={s?.simulated_data_present} subtitle={s ? `최근 7일 (${s.period.from} ~ ${s.period.to}, 한국 표준시)` : undefined} actions={<CSVExportButton filters={range} label="기간 CSV" />} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      <div className="grid gap-4 lg:grid-cols-2">
        <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm lg:col-span-2" aria-labelledby="r-trend">
          <h2 id="r-trend" className="mb-3 font-semibold">일자별 위반 건수</h2>
          {s ? <ViolationTrendChart data={s.daily_trend} onSelectDay={(d) => nav(`/events?from=${d}&to=${d}`)} /> : <LoadingSkeleton />}
        </section>
        <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm" aria-labelledby="r-top">
          <h2 id="r-top" className="mb-3 font-semibold">기기별 위반 상위</h2>
          {s ? <BarList rows={s.top_devices.map((d) => ({ label: d.device_id, sub: d.alias ?? undefined, value: d.count }))} empty="기간 내 위반 기록이 없습니다"
            onSelect={(r) => nav(`/events?device_id=${encodeURIComponent(r.label)}&from=${s.period.from}&to=${s.period.to}`)} selectHint={() => '이 장비의 이벤트 보기'} /> : <LoadingSkeleton />}
        </section>
        <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm" aria-labelledby="r-lag">
          <h2 id="r-lag" className="mb-1 font-semibold">전송 지연 분포</h2>
          <p className="mb-3 text-xs text-slate-500">지연 = 서버 최초 수신 시각 − 기기 발생 시각. 음수는 기기 시계 오차로 분류합니다.</p>
          {s ? <BarList rows={lag} empty="기간 내 이벤트가 없습니다" /> : <LoadingSkeleton />}
        </section>
      </div>
    </>
  )
}
