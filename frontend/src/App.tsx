import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { AppShell } from './components/layout'
import { LoadingSkeleton, ToastProvider } from './components/ui'
import { LoginPage } from './pages/LoginPage'
import { DashboardPage } from './pages/DashboardPage'
import { EventsPage } from './pages/EventsPage'
import { DevicesPage } from './pages/DevicesPage'
import { MapPage } from './pages/MapPage'
import { ReportsPage } from './pages/ReportsPage'
import { OpsPage } from './pages/OpsPage'

/** Renders the app only for a logged-in admin; otherwise the login page. */
function AuthGate() {
  const { user, loading } = useAuth()
  if (loading) return <div className="p-8"><LoadingSkeleton rows={4} /></div>
  if (!user) return <LoginPage />
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<DashboardPage />} />
        <Route path="events" element={<EventsPage />} />
        <Route path="devices" element={<DevicesPage />} />
        <Route path="map" element={<MapPage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="ops" element={<OpsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <AuthGate />
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  )
}
