/** Server sends ISO-8601 with +09:00 already. We only reformat for compact, readable Korean display. */

export function formatKst(iso: string | null | undefined): string {
  if (!iso) return '-'
  // "2026-11-05T14:30:10.123+09:00" -> "2026-11-05 14:30:10" (offset is already Asia/Seoul)
  return iso.replace('T', ' ').replace(/(\.\d+)?[+-]\d{2}:\d{2}$|Z$/, '')
}

export function formatLag(seconds: number): string {
  if (seconds < -60) return '시계 오차 의심'
  const s = Math.max(0, seconds)
  if (s < 60) return `${s}초`
  if (s < 3600) return `${Math.round(s / 60)}분`
  return `${(s / 3600).toFixed(1)}시간`
}

export interface Freshness {
  label: string
  level: 'fresh' | 'stale' | 'old' | 'none'
}

/** "마지막 수신 N 전". This is last-heard-from time, never an online/offline claim. */
export function freshness(iso: string | null | undefined, now: Date = new Date()): Freshness {
  if (!iso) return { label: '수신 기록 없음', level: 'none' }
  const diffMs = now.getTime() - new Date(iso).getTime()
  const min = Math.floor(diffMs / 60000)
  if (min < 1) return { label: '방금 전', level: 'fresh' }
  if (min < 60) return { label: `${min}분 전`, level: 'fresh' }
  const h = Math.floor(min / 60)
  if (h < 24) return { label: `${h}시간 전`, level: 'fresh' }
  const d = Math.floor(h / 24)
  return { label: `${d}일 전`, level: d >= 3 ? 'old' : 'stale' }
}

export function formatGps(lat: number | null, lon: number | null): string {
  return lat == null || lon == null ? '위치 없음' : `${lat.toFixed(6)}, ${lon.toFixed(6)}`
}

export function isoDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export const LAG_BUCKET_LABELS: Record<string, string> = {
  under_10s: '10초 미만',
  '10s_to_1m': '10초~1분',
  '1m_to_10m': '1~10분',
  over_10m: '10분 이상',
  clock_skew: '시계 오차 의심',
}
export const LAG_BUCKET_ORDER = ['under_10s', '10s_to_1m', '1m_to_10m', 'over_10m', 'clock_skew']
