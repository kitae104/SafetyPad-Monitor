import type { EventFilters } from './types'

const BASE = '/api/v1/admin'

export class ApiError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn
}

export function readCookie(name: string, source: string = document.cookie): string | null {
  const m = source.split('; ').find((c) => c.startsWith(name + '='))
  return m ? decodeURIComponent(m.slice(name.length + 1)) : null
}

async function ensureCsrf(): Promise<string> {
  const existing = readCookie('XSRF-TOKEN')
  if (existing) return existing
  const r = await fetch(`${BASE}/csrf`, { credentials: 'same-origin' })
  const body = (await r.json()) as { token: string }
  return readCookie('XSRF-TOKEN') ?? body.token
}

export async function api<T>(path: string, opts: { method?: string; body?: unknown } = {}): Promise<T> {
  const method = opts.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (method !== 'GET') {
    headers['Content-Type'] = 'application/json'
    headers['X-XSRF-TOKEN'] = await ensureCsrf()
  }
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    credentials: 'same-origin',
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  })
  if (!res.ok) {
    let code = 'ERROR'
    let message = res.statusText
    try {
      const j = (await res.json()) as { error?: string; message?: string }
      code = j.error ?? code
      message = j.message ?? message
    } catch {
      /* non-JSON error body */
    }
    if (res.status === 401 && path !== '/login' && path !== '/me') onUnauthorized?.()
    throw new ApiError(res.status, code, message)
  }
  return (await res.json()) as T
}

/** Builds a query string, skipping empty values. */
export function qs(params: Record<string, string | number | undefined | null>): string {
  const u = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && String(v) !== '') u.set(k, String(v))
  }
  const s = u.toString()
  return s ? `?${s}` : ''
}

export function eventsQuery(f: EventFilters): string {
  return qs({ ...f })
}

/** Export is a plain same-origin GET link so the browser streams the download with the session cookie. */
export function csvExportUrl(f: EventFilters): string {
  return `${BASE}/events/export.csv${qs({ from: f.from, to: f.to, device_id: f.device_id, min_lag_seconds: f.min_lag_seconds, has_gps: f.has_gps })}`
}
