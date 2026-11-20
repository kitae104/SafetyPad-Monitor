import type { MapEventPoint } from '../api/types'

export interface DeviceGroup {
  device_id: string
  alias: string | null
  count: number
  /** ISO of the most recent violation (event_time, Asia/Seoul offset as sent by the server). */
  latest: string
  simulated: boolean
}

/** Violations per device, most violations first (ties: most recent first, then ID) so the busiest locations lead the list. */
export function groupByDevice(points: MapEventPoint[]): DeviceGroup[] {
  const m = new Map<string, DeviceGroup>()
  for (const p of points) {
    const g = m.get(p.device_id)
    if (!g) m.set(p.device_id, { device_id: p.device_id, alias: p.alias, count: 1, latest: p.event_time, simulated: p.simulated })
    else {
      g.count++
      if (new Date(p.event_time) > new Date(g.latest)) g.latest = p.event_time
    }
  }
  return [...m.values()].sort((a, b) => b.count - a.count || new Date(b.latest).getTime() - new Date(a.latest).getTime() || a.device_id.localeCompare(b.device_id))
}

export interface ClusterSummary {
  count: number
  deviceCount: number
  /** Up to `top` devices inside the cluster, most violations first. */
  top: { device_id: string; count: number }[]
  latest: string
  earliest: string
}

/** What is inside a map cluster: the question "위반 12건이라는데 무엇이 모여 있나?" */
export function summarizeCluster(points: MapEventPoint[], top = 3): ClusterSummary {
  const groups = groupByDevice(points)
  let latest = points[0]?.event_time ?? ''
  let earliest = latest
  for (const p of points) {
    if (new Date(p.event_time) > new Date(latest)) latest = p.event_time
    if (new Date(p.event_time) < new Date(earliest)) earliest = p.event_time
  }
  return { count: points.length, deviceCount: groups.length, top: groups.slice(0, top).map((g) => ({ device_id: g.device_id, count: g.count })), latest, earliest }
}

/** The selected device's violations, newest first. */
export function violationsOf(points: MapEventPoint[], deviceId: string): MapEventPoint[] {
  return points.filter((p) => p.device_id === deviceId).sort((a, b) => new Date(b.event_time).getTime() - new Date(a.event_time).getTime())
}

/** Today in Asia/Seoul as YYYY-MM-DD, independent of the browser's time zone. */
export function seoulToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}

/** YYYY-MM-DD shifted by whole days (pure calendar arithmetic, no time zone involved). */
export function shiftDate(ymd: string, days: number): string {
  const [y, m, d] = ymd.split('-').map(Number)
  const t = new Date(Date.UTC(y, m - 1, d + days))
  return t.toISOString().slice(0, 10)
}
