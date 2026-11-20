import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle, CheckCircle2, ChevronRight, Inbox, X } from 'lucide-react'
import { freshness } from '../lib/format'

export function LoadingSkeleton({ rows = 3, className = '' }: { rows?: number; className?: string }) {
  return (
    <div role="status" aria-label="불러오는 중" className={`animate-pulse space-y-3 ${className}`}>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="h-5 rounded bg-slate-200" style={{ width: `${90 - i * 12}%` }} />
      ))}
    </div>
  )
}

export function EmptyState({ title, hint, icon }: { title: string; hint?: string; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-4 py-10 text-center text-slate-500">
      <span aria-hidden="true" className="text-slate-400">{icon ?? <Inbox size={32} />}</span>
      <p className="font-medium text-slate-700">{title}</p>
      {hint && <p className="max-w-sm text-sm">{hint}</p>}
    </div>
  )
}

export function ErrorNotice({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex items-center gap-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
      <AlertTriangle size={18} aria-hidden="true" />
      <span className="flex-1">{message}</span>
      {onRetry && (
        <button type="button" onClick={onRetry} className="min-h-11 rounded-md px-3 font-medium underline focus-visible:outline-2">
          다시 시도
        </button>
      )}
    </div>
  )
}

/** Every demo screen must visibly say the data is fictional. */
export function DemoBadge({ className = '' }: { className?: string }) {
  return (
    <span
      data-testid="demo-badge"
      className={`inline-flex items-center rounded-full border border-amber-300 bg-amber-50 px-2.5 py-0.5 text-xs font-semibold text-amber-800 ${className}`}
    >
      가상 데이터
    </span>
  )
}

export function SummaryMetricCard({
  label,
  value,
  hint,
  tone = 'default',
  testId,
  to,
  cta = '자세히 보기',
}: {
  label: string
  value: ReactNode
  hint?: string
  tone?: 'default' | 'warn'
  testId?: string
  /** When set the whole card is a link to the screen that lists what the number counts. */
  to?: string
  cta?: string
}) {
  const body = (
    <>
      <p className="text-sm font-medium text-slate-500">{label}</p>
      <p className={`mt-1 text-3xl font-bold tabular-nums ${tone === 'warn' ? 'text-amber-600' : 'text-slate-900'}`}>{value}</p>
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
      {to && <p aria-hidden="true" className="mt-2 inline-flex items-center text-xs font-medium text-brand">{cta}<ChevronRight size={14} /></p>}
    </>
  )
  const base = 'rounded-xl border border-slate-200 bg-white p-4 shadow-sm'
  if (!to) return <section aria-label={label} data-testid={testId} className={base}>{body}</section>
  return (
    <Link to={to} data-testid={testId} aria-label={`${label} ${value}${hint ? `, ${hint}` : ''}. ${cta}`}
      className={`${base} block transition hover:border-brand hover:shadow-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand`}>
      {body}
    </Link>
  )
}

export function BatteryGauge({ level, threshold = 20 }: { level: number | null; threshold?: number }) {
  if (level == null) return <span className="text-slate-400">-</span>
  const low = level <= threshold
  return (
    <span className="inline-flex items-center gap-2" title={`배터리 ${level}%`}>
      <span aria-hidden="true" className="h-2 w-12 overflow-hidden rounded-full bg-slate-200">
        <span className={`block h-full ${low ? 'bg-amber-500' : 'bg-brand'}`} style={{ width: `${level}%` }} />
      </span>
      <span className={`tabular-nums text-sm ${low ? 'font-semibold text-amber-700' : ''}`}>{level}%{low && ' 주의'}</span>
    </span>
  )
}

const FRESH_STYLES = {
  fresh: 'bg-blue-50 text-blue-800 border-blue-200',
  stale: 'bg-amber-50 text-amber-800 border-amber-200',
  old: 'bg-slate-100 text-slate-600 border-slate-300',
  none: 'bg-slate-100 text-slate-500 border-slate-300',
} as const

/** Shows when we LAST heard from the device. It deliberately never says online/offline. */
export function LastSeenIndicator({ iso, now }: { iso: string | null; now?: Date }) {
  const f = freshness(iso, now)
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium ${FRESH_STYLES[f.level]}`}
      title={iso ? `최근 수신: ${iso}` : '수신 기록 없음'}
    >
      최근 수신 · {f.label}
    </span>
  )
}

/* ---------- Toast ---------- */
interface ToastItem { id: number; text: string; kind: 'ok' | 'error' }
const ToastCtx = createContext<(text: string, kind?: 'ok' | 'error') => void>(() => {})
export const useToast = () => useContext(ToastCtx)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const seq = useRef(0)
  const push = useCallback((text: string, kind: 'ok' | 'error' = 'ok') => {
    const id = ++seq.current
    setItems((l) => [...l, { id, text, kind }])
    setTimeout(() => setItems((l) => l.filter((t) => t.id !== id)), 5000)
  }, [])
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div aria-live="polite" className="fixed bottom-4 right-4 z-50 flex max-w-sm flex-col gap-2">
        {items.map((t) => (
          <div key={t.id} role="status" className={`flex items-start gap-2 rounded-lg px-4 py-3 text-sm text-white shadow-lg ${t.kind === 'ok' ? 'bg-slate-800' : 'bg-red-700'}`}>
            {t.kind === 'ok' ? <CheckCircle2 size={18} aria-hidden="true" /> : <AlertTriangle size={18} aria-hidden="true" />}
            <span>{t.text}</span>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}

/* ---------- Dialogs ---------- */
export function Dialog({ title, onClose, children, wide = false }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null
    ref.current?.focus()
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      prev?.focus()
    }
  }, [onClose])
  return (
    <div className="fixed inset-0 z-40 flex items-end justify-center bg-slate-900/50 p-0 sm:items-center sm:p-4" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        ref={ref}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`max-h-[90vh] w-full overflow-y-auto rounded-t-2xl bg-white p-5 shadow-xl outline-none sm:rounded-2xl ${wide ? 'sm:max-w-2xl' : 'sm:max-w-md'}`}
      >
        <div className="mb-3 flex items-start justify-between gap-3">
          <h2 className="text-lg font-semibold text-slate-900">{title}</h2>
          <button type="button" onClick={onClose} aria-label="닫기" className="-m-2 inline-flex size-11 items-center justify-center rounded-md text-slate-500 hover:bg-slate-100">
            <X size={20} aria-hidden="true" />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}

export function ConfirmDialog({
  title,
  message,
  confirmLabel,
  danger = false,
  onConfirm,
  onCancel,
}: {
  title: string
  message: ReactNode
  confirmLabel: string
  danger?: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  return (
    <Dialog title={title} onClose={onCancel}>
      <div className="text-sm text-slate-700">{message}</div>
      <div className="mt-5 flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="min-h-11 rounded-lg border border-slate-300 px-4 text-sm font-medium hover:bg-slate-50">
          취소
        </button>
        <button
          type="button"
          onClick={onConfirm}
          className={`min-h-11 rounded-lg px-4 text-sm font-semibold text-white ${danger ? 'bg-red-600 hover:bg-red-700' : 'bg-brand hover:bg-blue-700'}`}
        >
          {confirmLabel}
        </button>
      </div>
    </Dialog>
  )
}

export const btnPrimary =
  'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-brand px-4 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50'
export const btnSecondary =
  'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-slate-300 bg-white px-4 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50'
export const inputCls =
  'min-h-11 w-full rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-900 placeholder:text-slate-400'
