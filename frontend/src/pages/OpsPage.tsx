import { useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { api, ApiError } from '../api/client'
import { PageHeader } from '../components/layout'
import { ConfirmDialog, btnSecondary, useToast } from '../components/ui'
import { AdminAccounts } from '../components/AdminAccounts'

export function OpsPage() {
  const { config } = useAuth()
  const toast = useToast()
  const [confirmReset, setConfirmReset] = useState(false)

  const call = async (path: string, body: unknown, okText: string) => {
    try {
      await api(path, { method: 'POST', body })
      toast(okText)
    } catch (e) {
      toast(e instanceof ApiError ? e.message : '요청을 처리하지 못했습니다.', 'error')
    }
  }

  const flags: [string, string][] = [
    ['주기 GPS 수신 (device-locations)', config?.location_ping_ingest_enabled ? '켜짐' : '꺼짐 — 펌웨어 규격 승인 전'],
    ['이동 경로 보기', config?.route_enabled ? '켜짐 (데모 데이터 한정)' : '꺼짐'],
    ['MQTT 수신', !config?.mqtt_enabled ? '꺼짐 (REST만 사용)' : [config.mqtt_tls_port ? `TLS ${config.mqtt_tls_port}` : null, config.mqtt_plaintext_port ? `평문 ${config.mqtt_plaintext_port} (개발 전용)` : null].filter(Boolean).join(' · ')],
    ['지도 제공자', config?.map_provider === 'mock' ? '키 없는 대체 지도' : (config?.map_provider ?? '-')],
    ['표시 시간대', config?.timezone ?? 'Asia/Seoul'],
  ]
  return (
    <>
      <PageHeader title="운영 설정" subtitle="서버 설정에서 읽어 온 값입니다. 이 화면에서는 변경할 수 없습니다." />
      <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm" aria-labelledby="flags">
        <h2 id="flags" className="mb-3 font-semibold">기능 상태</h2>
        <dl className="divide-y divide-slate-100 text-sm">
          {flags.map(([k, v]) => (
            <div key={k} className="flex flex-wrap justify-between gap-2 py-2.5"><dt className="text-slate-600">{k}</dt><dd className="font-medium">{v}</dd></div>
          ))}
        </dl>
      </section>
      <section className="mt-4 rounded-xl border border-slate-200 bg-white p-4 shadow-sm" aria-labelledby="data-policy">
        <h2 id="data-policy" className="mb-2 font-semibold">데이터 보존·개인정보</h2>
        <p className="text-sm text-slate-600">보존 기간과 위치정보 처리 근거는 아직 확정되지 않았습니다. 확정 전에는 원시 이벤트를 삭제하거나 수정하지 않으며, CSV 내보내기와 관리자 변경은 감사 로그에 기록됩니다.</p>
      </section>
      <AdminAccounts />
      {config?.demo_tools && (
        <section className="mt-4 rounded-xl border border-amber-300 bg-amber-50 p-4" aria-labelledby="demo-tools" data-testid="demo-tools">
          <h2 id="demo-tools" className="mb-1 font-semibold text-amber-900">가상 데이터 도구 (개발 환경 전용)</h2>
          <p className="mb-3 text-sm text-amber-900">가상 데이터는 개발 프로파일에서만 불러올 수 있으며 운영 환경에는 이 기능이 존재하지 않습니다.</p>
          <p className="mb-3 text-sm text-amber-900">화면 위쪽의 <strong>데이터 랜덤 생성</strong> 버튼을 눌러 두면, 눌린 동안만 가상 장비의 위반 이벤트가 임의의 시간에 계속 들어오고 화면이 5초마다 갱신됩니다. 다시 누르면 멈추며, 화면을 닫으면 30초 안에 멈춥니다. 생성된 이벤트는 삭제할 수 없으므로(원시 데이터 불변) 아래 ‘가상 데이터 초기화’로만 지울 수 있습니다.</p>
          <div className="flex flex-wrap gap-2">
            <button type="button" className={btnSecondary} onClick={() => call('/dev/import-demo', {}, '가상 데이터를 불러왔습니다.')}>가상 데이터 불러오기</button>
            <button type="button" className={`${btnSecondary} border-red-300 text-red-700`} onClick={() => setConfirmReset(true)}>가상 데이터 초기화</button>
          </div>
        </section>
      )}
      {confirmReset && (
        <ConfirmDialog danger title="가상 데이터 초기화" message="가상으로 표시된 장비의 이벤트와 위치 기록만 삭제합니다. 실제 수신 데이터에는 영향이 없습니다." confirmLabel="초기화"
          onCancel={() => setConfirmReset(false)} onConfirm={() => { setConfirmReset(false); void call('/dev/reset-demo', { confirm: 'RESET_DEMO_DATA' }, '가상 데이터를 초기화했습니다.') }} />
      )}
    </>
  )
}
