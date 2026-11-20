import { useState, type ReactNode } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { BarChart3, Cpu, LayoutDashboard, LogOut, MapPin, Menu, Play, Settings, ShieldAlert, Square, X } from 'lucide-react'
import { useAuth } from '../auth/AuthContext'
import { LiveDataProvider, useLiveData } from '../lib/liveData'
import { DemoBadge } from './ui'

const NAV = [
  { to: '/', label: '대시보드', icon: LayoutDashboard, end: true },
  { to: '/events', label: '위반 이벤트', icon: ShieldAlert },
  { to: '/devices', label: '장비 관리', icon: Cpu },
  { to: '/map', label: '위치·경로', icon: MapPin },
  { to: '/reports', label: '통계 리포트', icon: BarChart3 },
  { to: '/ops', label: '운영 설정', icon: Settings },
]

export function SidebarNav({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <nav aria-label="주 메뉴" className="flex flex-col gap-1">
      {NAV.map(({ to, label, icon: Icon, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          onClick={onNavigate}
          className={({ isActive }) =>
            `flex min-h-11 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors ${
              isActive ? 'bg-white/15 text-white' : 'text-blue-100 hover:bg-white/10'
            }`
          }
        >
          <Icon size={18} aria-hidden="true" />
          {label}
        </NavLink>
      ))}
    </nav>
  )
}

function Brand() {
  return (
    <div className="px-1">
      <p className="text-lg font-bold text-white">◈ SafetyPad Monitor</p>
      <p className="text-xs text-blue-200">IoT 안전발판 통합 관제</p>
    </div>
  )
}

/** DEV ONLY (server-gated): while pressed, fictional events arrive at random times. Press again to stop. */
function LiveGeneratorButton() {
  const { available, running, busy, generated, toggle } = useLiveData()
  if (!available) return null
  const label = running ? `가상 데이터 생성 중 · ${generated}건` : '데이터 랜덤 생성'
  const shortLabel = running ? `생성 중 · ${generated}건` : '랜덤 생성'      // phones: the long label would not fit next to the logout button
  return (
    <button
      type="button"
      aria-pressed={running}
      aria-label={running ? `${label} (누르면 중지)` : '데이터 랜덤 생성 시작'}
      title="눌러 둔 동안만 가상 데이터가 임의의 시간에 계속 들어옵니다. 다시 누르면 멈추고, 화면을 닫아도 30초 안에 멈춥니다."
      disabled={busy}
      data-testid="live-generator"
      onClick={() => void toggle()}
      className={`inline-flex min-h-11 items-center gap-2 rounded-lg border px-3 text-sm font-medium disabled:opacity-60 ${
        running ? 'border-red-300 bg-red-50 text-red-800 hover:bg-red-100' : 'border-amber-300 bg-amber-50 text-amber-900 hover:bg-amber-100'
      }`}
    >
      {running ? <Square size={14} aria-hidden="true" className="fill-current" /> : <Play size={14} aria-hidden="true" className="fill-current" />}
      <span className="hidden whitespace-nowrap sm:inline" data-testid="live-generator-label">{label}</span>
      <span className="whitespace-nowrap sm:hidden" data-testid="live-generator-label-short">{shortLabel}</span>
    </button>
  )
}

export function TopBar({ onMenu }: { onMenu: () => void }) {
  const { user, logout } = useAuth()
  const { running, toggle } = useLiveData()
  const navigate = useNavigate()
  return (
    <header className="sticky top-0 z-30 flex items-center gap-2 border-b border-slate-200 bg-white/95 px-4 py-2 backdrop-blur lg:px-8">
      <button type="button" onClick={onMenu} aria-label="메뉴 열기" className="-ml-2 inline-flex size-11 items-center justify-center rounded-lg text-slate-700 hover:bg-slate-100 lg:hidden">
        <Menu size={22} aria-hidden="true" />
      </button>
      <p className="hidden font-semibold text-slate-900 min-[480px]:block lg:hidden">SafetyPad</p>
      <div className="ml-auto flex items-center gap-2 text-sm">
        <LiveGeneratorButton />
        <span className="hidden text-slate-600 sm:inline" data-testid="signed-in-as">
          {user?.username} · 관리자
        </span>
        <button
          type="button"
          onClick={async () => {
            if (running) await toggle()                 // stop generating before the session ends
            await logout()
            navigate('/')
          }}
          className="inline-flex min-h-11 items-center gap-2 rounded-lg border border-slate-300 px-3 font-medium text-slate-700 hover:bg-slate-50"
        >
          <LogOut size={16} aria-hidden="true" />
          로그아웃
        </button>
      </div>
    </header>
  )
}

export function AppShell() {
  // The drawer is "open" only for the path it was opened on, so any navigation closes it without an effect.
  const loc = useLocation()
  const [openPath, setOpenPath] = useState<string | null>(null)
  const open = openPath === loc.pathname
  const setOpen = (v: boolean) => setOpenPath(v ? loc.pathname : null)
  return (
    <LiveDataProvider>
    <div className="min-h-screen lg:grid lg:grid-cols-[232px_1fr]">
      <aside className="hidden bg-sidebar p-5 lg:block">
        <div className="sticky top-5 space-y-8">
          <Brand />
          <SidebarNav />
        </div>
      </aside>
      {open && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="메뉴">
          <button type="button" aria-label="메뉴 닫기" className="absolute inset-0 bg-slate-900/50" onClick={() => setOpen(false)} />
          <div className="absolute inset-y-0 left-0 w-72 max-w-[85vw] space-y-8 bg-sidebar p-5">
            <div className="flex items-start justify-between">
              <Brand />
              <button type="button" onClick={() => setOpen(false)} aria-label="메뉴 닫기" className="inline-flex size-11 items-center justify-center rounded-lg text-white hover:bg-white/10">
                <X size={20} aria-hidden="true" />
              </button>
            </div>
            <SidebarNav onNavigate={() => setOpen(false)} />
          </div>
        </div>
      )}
      <div className="min-w-0">
        <TopBar onMenu={() => setOpen(true)} />
        <main className="mx-auto w-full max-w-[1500px] px-4 py-5 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
    </LiveDataProvider>
  )
}

export function PageHeader({ title, subtitle, actions, demo }: { title: string; subtitle?: ReactNode; actions?: ReactNode; demo?: boolean }) {
  return (
    <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="flex flex-wrap items-center gap-2 text-2xl font-bold text-slate-900">
          {title}
          {demo && <DemoBadge />}
        </h1>
        {subtitle && <p className="mt-1 text-sm text-slate-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

export function DemoBanner() {
  return (
    <p role="note" className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-900">
      <strong>가상 데이터</strong> — 인하공전 주변을 배경으로 임의 생성한 테스트 기록입니다. 실제 장비·위치·위반 기록이 아니며 증거나 내비게이션 용도로 쓸 수 없습니다.
    </p>
  )
}
