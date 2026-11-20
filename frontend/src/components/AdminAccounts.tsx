import { useState } from 'react'
import { KeyRound, UserPlus } from 'lucide-react'
import { api, ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useApi } from '../lib/useApi'
import { formatKst } from '../lib/format'
import { ConfirmDialog, Dialog, ErrorNotice, LoadingSkeleton, btnPrimary, btnSecondary, inputCls, useToast } from './ui'

interface AdminUser { username: string; role: string; enabled: boolean; created_at: string; last_login_at: string | null }

const MIN_PW = 12

function PasswordForm({ title, needsCurrent, onSubmit, onClose }: { title: string; needsCurrent: boolean; onSubmit: (current: string, next: string) => Promise<void>; onClose: () => void }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (next.length < MIN_PW) return setError(`비밀번호는 ${MIN_PW}자 이상이어야 합니다.`)
    try {
      await onSubmit(current, next)
      onClose()
    } catch (err) {
      setError(err instanceof ApiError ? (err.status === 403 && needsCurrent ? '현재 비밀번호가 올바르지 않습니다.' : err.message) : '처리하지 못했습니다.')
    }
  }
  return (
    <Dialog title={title} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>}
        {needsCurrent && <label className="block text-sm font-medium">현재 비밀번호<input type="password" required autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} className={`${inputCls} mt-1`} /></label>}
        <label className="block text-sm font-medium">새 비밀번호 ({MIN_PW}자 이상)<input type="password" required minLength={MIN_PW} maxLength={128} autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} className={`${inputCls} mt-1`} /></label>
        <div className="flex justify-end gap-2"><button type="button" className={btnSecondary} onClick={onClose}>취소</button><button type="submit" className={btnPrimary}>변경</button></div>
      </form>
    </Dialog>
  )
}

export function AdminAccounts() {
  const { user } = useAuth()
  const toast = useToast()
  const { data, error, loading, reload } = useApi<AdminUser[]>('/users')
  const [creating, setCreating] = useState(false)
  const [reset, setReset] = useState<string | null>(null)
  const [changeMine, setChangeMine] = useState(false)
  const [toggle, setToggle] = useState<AdminUser | null>(null)

  const run = async (fn: () => Promise<unknown>, okText: string) => {
    try {
      await fn()
      toast(okText)
      reload()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : '요청을 처리하지 못했습니다.', 'error')
    }
  }

  return (
    <section className="mt-4 rounded-xl border border-slate-200 bg-white p-4 shadow-sm" aria-labelledby="accounts" data-testid="admin-accounts">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 id="accounts" className="font-semibold">관리자 계정</h2>
        <div className="flex flex-wrap gap-2">
          <button type="button" className={btnSecondary} onClick={() => setChangeMine(true)}><KeyRound size={16} aria-hidden="true" />내 비밀번호 변경</button>
          <button type="button" className={btnPrimary} onClick={() => setCreating(true)}><UserPlus size={16} aria-hidden="true" />관리자 추가</button>
        </div>
      </div>
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {loading && !data && <LoadingSkeleton rows={3} />}
      {data && (
        <ul className="divide-y divide-slate-100 text-sm" aria-label="관리자 목록">
          {data.map((u) => (
            <li key={u.username} className="flex flex-wrap items-center justify-between gap-2 py-2.5">
              <span>
                <b>{u.username}</b>{u.username === user?.username && <span className="ml-1 text-xs text-slate-500">(나)</span>}
                {!u.enabled && <span className="ml-2 rounded bg-slate-200 px-1.5 py-0.5 text-xs">비활성</span>}
                <span className="block text-xs text-slate-500">마지막 로그인 {formatKst(u.last_login_at)}</span>
              </span>
              {u.username !== user?.username && (
                <span className="flex gap-1">
                  <button type="button" className="min-h-11 px-2 text-sm font-medium text-brand hover:underline" onClick={() => setReset(u.username)}>비밀번호 재설정</button>
                  <button type="button" className="min-h-11 px-2 text-sm font-medium text-red-700 hover:underline" onClick={() => setToggle(u)}>{u.enabled ? '비활성화' : '활성화'}</button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      <p className="mt-3 text-xs text-slate-500">모든 계정은 동일한 관리자 권한입니다(역할 분리는 확정 전). 계정 변경은 감사 로그에 기록되며, 비활성화하면 열려 있는 세션도 즉시 종료됩니다.</p>

      {creating && <CreateDialog onClose={() => setCreating(false)} onDone={() => { toast('관리자를 추가했습니다.'); reload() }} />}
      {reset && <PasswordForm title={`${reset} 비밀번호 재설정`} needsCurrent={false} onClose={() => setReset(null)} onSubmit={async (_c, n) => { await api(`/users/${encodeURIComponent(reset)}/reset-password`, { method: 'POST', body: { new_password: n } }); toast('비밀번호를 재설정했습니다.') }} />}
      {changeMine && <PasswordForm title="내 비밀번호 변경" needsCurrent onClose={() => setChangeMine(false)} onSubmit={async (c, n) => { await api('/me/password', { method: 'POST', body: { current_password: c, new_password: n } }); toast('비밀번호를 변경했습니다.') }} />}
      {toggle && (
        <ConfirmDialog danger={toggle.enabled} title={toggle.enabled ? '관리자 비활성화' : '관리자 활성화'} confirmLabel={toggle.enabled ? '비활성화' : '활성화'}
          message={toggle.enabled ? `${toggle.username} 계정의 로그인과 열려 있는 세션을 즉시 차단합니다.` : `${toggle.username} 계정을 다시 사용할 수 있게 합니다.`}
          onCancel={() => setToggle(null)} onConfirm={() => { const t = toggle; setToggle(null); void run(() => api(`/users/${encodeURIComponent(t.username)}/enabled`, { method: 'POST', body: { enabled: !t.enabled } }), t.enabled ? '비활성화했습니다.' : '활성화했습니다.') }} />
      )}
    </section>
  )
}

function CreateDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      await api('/users', { method: 'POST', body: { username: username.trim(), password } })
      onDone()
      onClose()
    } catch (err) {
      setError(err instanceof ApiError ? (err.status === 409 ? '이미 있는 아이디입니다.' : err.message) : '추가하지 못했습니다.')
    }
  }
  return (
    <Dialog title="관리자 추가" onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>}
        <label className="block text-sm font-medium">아이디 (영문·숫자·._- 3~64자)<input required value={username} onChange={(e) => setUsername(e.target.value)} pattern="[A-Za-z0-9._\-]{3,64}" autoComplete="off" className={`${inputCls} mt-1`} /></label>
        <label className="block text-sm font-medium">초기 비밀번호 ({MIN_PW}자 이상)<input type="password" required minLength={MIN_PW} maxLength={128} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} className={`${inputCls} mt-1`} /></label>
        <p className="text-xs text-slate-500">초기 비밀번호는 안전한 경로로 전달하고, 첫 로그인 후 변경하도록 안내하세요.</p>
        <div className="flex justify-end gap-2"><button type="button" className={btnSecondary} onClick={onClose}>취소</button><button type="submit" className={btnPrimary}>추가</button></div>
      </form>
    </Dialog>
  )
}
