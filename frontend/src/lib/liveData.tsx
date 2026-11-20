import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { useToast } from '../components/ui'
import type { LiveStatus } from '../api/types'

/**
 * DEV ONLY demo feature: "데이터 랜덤 생성". While the button is pressed the server adds fictional events at random
 * times; this provider renews the server-side lease every few seconds and nudges every data hook to refetch (`tick`),
 * so the screens update as if data were arriving live. If the page closes, the lease lapses and the server stops by itself.
 * The server decides whether the feature exists (`config.demo_tools`); production never offers it.
 */
export interface LiveValue {
  available: boolean
  running: boolean        // the button is pressed
  busy: boolean
  generated: number       // events added in the current run
  tick: number            // increments whenever fresh data may exist
  toggle: () => Promise<void>
}

const NOOP: LiveValue = { available: false, running: false, busy: false, generated: 0, tick: 0, toggle: async () => {} }
const LiveCtx = createContext<LiveValue>(NOOP)

export const useLiveData = () => useContext(LiveCtx)
export const useLiveTick = () => useContext(LiveCtx).tick

export const LIVE_PATH = '/dev/live-generator'
export const HEARTBEAT_MS = 5000

export function LiveDataProvider({ children }: { children: ReactNode }) {
  const { config } = useAuth()
  const toast = useToast()
  const available = config?.demo_tools === true
  const [pressed, setPressed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [generated, setGenerated] = useState(0)
  const [tick, setTick] = useState(0)

  // Adopt a run that is already in progress (e.g. after a page reload within the lease).
  useEffect(() => {
    if (!available) return
    let off = false
    api<LiveStatus>(LIVE_PATH)
      .then((s) => {
        if (off || !s.running) return
        setPressed(true)
        setGenerated(s.generated)
      })
      .catch(() => {})
    return () => {
      off = true
    }
  }, [available])

  // While pressed: renew the lease and refresh the screens. A lapsed run is simply started again by the renewal.
  useEffect(() => {
    if (!pressed) return
    let off = false
    const id = setInterval(() => {
      api<LiveStatus>(LIVE_PATH, { method: 'POST', body: { enabled: true } })
        .then((s) => {
          if (off) return
          setGenerated(s.generated)
          setTick((t) => t + 1)
        })
        .catch((e) => {
          if (off) return
          setPressed(false)
          toast(e instanceof ApiError ? e.message : '가상 데이터 생성이 중단되었습니다.', 'error')
        })
    }, HEARTBEAT_MS)
    return () => {
      off = true
      clearInterval(id)
    }
  }, [pressed, toast])

  const toggle = useCallback(async () => {
    if (busy) return
    setBusy(true)
    const next = !pressed
    try {
      const s = await api<LiveStatus>(LIVE_PATH, { method: 'POST', body: { enabled: next } })
      setPressed(next)
      setGenerated(s.generated)
      setTick((t) => t + 1)
    } catch (e) {
      toast(e instanceof ApiError ? e.message : '요청을 처리하지 못했습니다.', 'error')
    } finally {
      setBusy(false)
    }
  }, [busy, pressed, toast])

  const value = useMemo<LiveValue>(
    () => ({ available, running: pressed, busy, generated, tick, toggle }),
    [available, pressed, busy, generated, tick, toggle],
  )
  return <LiveCtx.Provider value={value}>{children}</LiveCtx.Provider>
}
