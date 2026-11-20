import { useState, type FormEvent } from 'react'
import { LogIn } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'
import { ApiError } from '../api/client'
import { btnPrimary, inputCls } from '../components/ui'

export function LoginPage() {
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(username.trim(), password)
    } catch (err) {
      if (err instanceof ApiError && err.status === 429) setError('로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.')
      else if (err instanceof ApiError && err.status === 401) setError('아이디 또는 비밀번호가 올바르지 않습니다.')
      else setError('서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-gradient-to-b from-sidebar to-blue-900 p-4">
      <form onSubmit={submit} className="w-full max-w-sm space-y-4 rounded-2xl bg-white p-6 shadow-xl" aria-labelledby="login-title">
        <div>
          <p className="text-sm font-semibold text-brand">◈ SafetyPad Monitor</p>
          <h1 id="login-title" className="mt-1 text-2xl font-bold text-slate-900">관리자 로그인</h1>
        </div>
        {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>}
        <label className="block text-sm font-medium text-slate-700">
          아이디
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required maxLength={64} className={`${inputCls} mt-1`} />
        </label>
        <label className="block text-sm font-medium text-slate-700">
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required maxLength={200} className={`${inputCls} mt-1`} />
        </label>
        <button type="submit" disabled={busy} className={`${btnPrimary} w-full`}>
          <LogIn size={16} aria-hidden="true" />
          {busy ? '확인 중…' : '로그인'}
        </button>
        <p className="text-xs text-slate-500">관리자 계정은 서버 운영자가 발급합니다. 기기 API 키로는 로그인할 수 없습니다.</p>
      </form>
    </main>
  )
}
