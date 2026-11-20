import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { useLiveTick } from './liveData'

export interface ApiState<T> {
  data: T | null
  error: string | null
  loading: boolean
  reload: () => void
}

interface Settled<T> {
  key: string
  data: T | null
  error: string | null
}

/**
 * Loads GET `path`; pass null to skip. `loading` is derived (the latest request has not settled yet),
 * and previous data stays visible while reloading to avoid layout jumps.
 */
export function useApi<T>(path: string | null): ApiState<T> {
  const [tick, setTick] = useState(0)
  const [settled, setSettled] = useState<Settled<T> | null>(null)
  const live = useLiveTick()                       // changes while the demo generator runs, so screens refetch
  const key = path === null ? '' : `${path}#${tick}#${live}`

  useEffect(() => {
    if (path === null) return
    let cancelled = false
    api<T>(path)
      .then((d) => !cancelled && setSettled({ key, data: d, error: null }))
      .catch((e) => {
        if (cancelled) return
        const message = e instanceof ApiError ? e.message : '서버에 연결할 수 없습니다'
        setSettled((prev) => ({ key, data: prev?.data ?? null, error: message }))
      })
    return () => {
      cancelled = true
    }
  }, [path, key])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  return {
    data: settled?.data ?? null,
    error: settled && settled.key === key ? settled.error : null,
    loading: path !== null && settled?.key !== key,
    reload,
  }
}
