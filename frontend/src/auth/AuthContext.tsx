import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, ApiError, setUnauthorizedHandler } from '../api/client'
import type { AppConfig, MeResponse } from '../api/types'

interface AuthState {
  user: MeResponse | null
  config: AppConfig | null
  loading: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const Ctx = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null)
  const [config, setConfig] = useState<AppConfig | null>(null)
  const [loading, setLoading] = useState(true)

  const loadConfig = useCallback(async () => {
    try {
      setConfig(await api<AppConfig>('/config'))
    } catch {
      setConfig(null)
    }
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(() => setUser(null))
    api<MeResponse>('/me')
      .then(async (u) => {
        setUser(u)
        await loadConfig()
      })
      .catch(() => setUser(null))
      .finally(() => setLoading(false))
    return () => setUnauthorizedHandler(null)
  }, [loadConfig])

  const value = useMemo<AuthState>(
    () => ({
      user,
      config,
      loading,
      login: async (username, password) => {
        // Make sure the CSRF cookie exists before the first mutation (login itself is CSRF protected).
        await fetch('/api/v1/admin/csrf', { credentials: 'same-origin' })
        const u = await api<MeResponse>('/login', { method: 'POST', body: { username, password } })
        setUser(u)
        await loadConfig()
      },
      logout: async () => {
        try {
          await api('/logout', { method: 'POST', body: {} })
        } catch (e) {
          if (!(e instanceof ApiError)) throw e
        }
        setUser(null)
      },
    }),
    [user, config, loading, loadConfig],
  )
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAuth(): AuthState {
  const v = useContext(Ctx)
  if (!v) throw new Error('useAuth outside AuthProvider')
  return v
}
