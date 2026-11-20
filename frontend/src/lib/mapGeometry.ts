/** Pure geometry for the keyless fallback map: local equirectangular projection (fine at city scale). */

export interface LatLon { lat: number; lon: number }
export interface View { center: LatLon; metersPerPixel: number }

export const INHA_ANCHOR: LatLon = { lat: 37.451448, lon: 126.651542 }
const M_PER_DEG_LAT = 111_320
export const MIN_MPP = 0.5
export const MAX_MPP = 400

const mPerDegLon = (lat: number) => M_PER_DEG_LAT * Math.cos((lat * Math.PI) / 180)

export function project(p: LatLon, view: View, w: number, h: number): { x: number; y: number } {
  const dx = (p.lon - view.center.lon) * mPerDegLon(view.center.lat)
  const dy = (p.lat - view.center.lat) * M_PER_DEG_LAT
  return { x: w / 2 + dx / view.metersPerPixel, y: h / 2 - dy / view.metersPerPixel }
}

export function unproject(x: number, y: number, view: View, w: number, h: number): LatLon {
  return {
    lat: view.center.lat - ((y - h / 2) * view.metersPerPixel) / M_PER_DEG_LAT,
    lon: view.center.lon + ((x - w / 2) * view.metersPerPixel) / mPerDegLon(view.center.lat),
  }
}

export function clampMpp(m: number): number {
  return Math.min(MAX_MPP, Math.max(MIN_MPP, m))
}

/** Smallest view that shows every point with padding; never zooms in closer than a ~300 m window. */
export function fitView(points: LatLon[], w: number, h: number, padding = 48): View {
  if (points.length === 0) return fitView([{ lat: INHA_ANCHOR.lat + 0.006, lon: INHA_ANCHOR.lon - 0.008 }, { lat: INHA_ANCHOR.lat - 0.006, lon: INHA_ANCHOR.lon + 0.008 }], w, h, padding)
  const lats = points.map((p) => p.lat)
  const lons = points.map((p) => p.lon)
  const minLat = Math.min(...lats), maxLat = Math.max(...lats)
  const minLon = Math.min(...lons), maxLon = Math.max(...lons)
  const center = { lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2 }
  const widthM = Math.max(300, (maxLon - minLon) * mPerDegLon(center.lat))
  const heightM = Math.max(300, (maxLat - minLat) * M_PER_DEG_LAT)
  const mpp = Math.max(widthM / Math.max(1, w - 2 * padding), heightM / Math.max(1, h - 2 * padding))
  return { center, metersPerPixel: clampMpp(mpp) }
}

export interface Cluster<T> { x: number; y: number; items: T[] }

/**
 * Distance-based clustering in screen space. Guarantees that any two resulting markers/clusters are at least
 * `radius` px apart, so a single marker can never hide under a neighbouring cluster circle.
 */
export function clusterPoints<T extends { x: number; y: number }>(items: T[], radius: number): Cluster<T>[] {
  const r2 = radius * radius
  const clusters: Cluster<T>[] = []
  const add = (c: Cluster<T>, it: T) => {
    const n = c.items.length
    c.x = (c.x * n + it.x) / (n + 1)
    c.y = (c.y * n + it.y) / (n + 1)
    c.items.push(it)
  }
  for (const it of items) {
    let best: Cluster<T> | null = null
    let bestD = r2
    for (const c of clusters) {
      const d = (c.x - it.x) ** 2 + (c.y - it.y) ** 2
      if (d < bestD) { best = c; bestD = d }
    }
    if (best) add(best, it)
    else clusters.push({ x: it.x, y: it.y, items: [it] })
  }
  // Centroids drift while clusters grow: merge any pair that ended up closer than `radius` (bounded passes).
  for (let pass = 0; pass < 5 && clusters.length <= 800; pass++) {
    let merged = false
    for (let i = 0; i < clusters.length; i++) {
      for (let j = clusters.length - 1; j > i; j--) {
        const a = clusters[i], b = clusters[j]
        if ((a.x - b.x) ** 2 + (a.y - b.y) ** 2 < r2) {
          const total = a.items.length + b.items.length
          a.x = (a.x * a.items.length + b.x * b.items.length) / total
          a.y = (a.y * a.items.length + b.y * b.items.length) / total
          a.items.push(...b.items)
          clusters.splice(j, 1)
          merged = true
        }
      }
    }
    if (!merged) break
  }
  return clusters
}

/** Picks a round scale-bar length (1/2/5 x 10^n metres) no wider than maxPx. */
export function scaleBar(metersPerPixel: number, maxPx = 120): { meters: number; px: number; label: string } {
  const rawMeters = metersPerPixel * maxPx
  const pow = Math.pow(10, Math.floor(Math.log10(rawMeters)))
  const meters = [5, 2, 1].map((m) => m * pow).find((m) => m <= rawMeters) ?? pow
  return { meters, px: meters / metersPerPixel, label: meters >= 1000 ? `${meters / 1000} km` : `${meters} m` }
}
