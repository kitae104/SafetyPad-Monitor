import { useEffect, useMemo, useRef, useState } from 'react'
import { INHA_ANCHOR } from '../../lib/mapGeometry'
import { formatKst } from '../../lib/format'
import { loadKakao, type KakaoNs } from './kakaoLoader'
import type { MapViewProps } from './KeylessMap'
import { MapInfoCard, type MapInfo } from './MapInfoCard'

/* eslint-disable @typescript-eslint/no-explicit-any */
const svgUrl = (fill: string, size: number, bang: boolean) =>
  'data:image/svg+xml;utf8,' + encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size * 2 + 4}" height="${size * 2 + 4}"><polygon points="${size + 2},2 ${size * 2 + 2},${size + 2} ${size + 2},${size * 2 + 2} 2,${size + 2}" fill="${fill}" stroke="#fff" stroke-width="2"/>` +
    (bang ? `<text x="${size + 2}" y="${size + 6}" text-anchor="middle" font-size="12" font-weight="800" fill="#fff">!</text>` : '') + '</svg>')

/** Cluster badge: a pill (not a circle) so "위반 N건" fits; size and darkness grow with the count. White text on dark blue keeps AA contrast. */
const CLUSTER_STYLES = [['#2563eb', 92, 34], ['#1d4ed8', 100, 38], ['#1e40af', 108, 42], ['#1e3a8a', 116, 46]].map(([bg, w, h]) => ({
  width: `${w}px`, height: `${h}px`, background: String(bg), color: '#fff', textAlign: 'center', fontSize: '13px', fontWeight: '700',
  lineHeight: `${(h as number) - 4}px`, borderRadius: `${(h as number) / 2}px`, border: '2px solid #fff', boxShadow: '0 1px 4px rgba(0,0,0,.35)', boxSizing: 'border-box', whiteSpace: 'nowrap',
}))

/**
 * Real map (Kakao Maps JS SDK). Same MapViewProps contract as KeylessMap.
 * Violation points are clustered markers; the route is drawn ONLY through real periodic GPS pings
 * (gaps are dashed grey) — a violation point alone is never connected into a "route".
 * `onError` lets the adapter fall back to the key-less map if the SDK/key/domain fails.
 */
export function KakaoMap({ points, selectedDeviceId, onSelectDevice, route, height = 400, focusPointId = null, appKey, onError }: MapViewProps & { appKey: string; onError: (e: Error) => void }) {
  const box = useRef<HTMLDivElement>(null)
  const mapRef = useRef<any>(null)
  const kRef = useRef<KakaoNs>(null)
  const [ready, setReady] = useState(false)
  const [active, setActive] = useState<string | null>(null)
  const [hoverCluster, setHoverCluster] = useState<(typeof points) | null>(null)
  const [pinnedId, setPinnedId] = useState<number | null>(null)
  const select = useRef(onSelectDevice)
  const errRef = useRef(onError)
  useEffect(() => { select.current = onSelectDevice; errRef.current = onError })
  const routePts = useMemo(() => (route?.sufficient ? route.points : []), [route])

  useEffect(() => {
    let dead = false
    let ro: ResizeObserver | undefined
    loadKakao(appKey).then((k) => {
      if (dead || !box.current) return
      kRef.current = k
      mapRef.current = new k.maps.Map(box.current, { center: new k.maps.LatLng(INHA_ANCHOR.lat, INHA_ANCHOR.lon), level: 5 })
      mapRef.current.addControl(new k.maps.ZoomControl(), k.maps.ControlPosition.RIGHT)
      if (typeof ResizeObserver !== 'undefined') { ro = new ResizeObserver(() => mapRef.current?.relayout()); ro.observe(box.current) }
      setReady(true)
    }).catch((e) => !dead && errRef.current(e instanceof Error ? e : new Error(String(e))))
    return () => { dead = true; ro?.disconnect(); mapRef.current = null }
  }, [appKey])

  useEffect(() => {
    const k = kRef.current, map = mapRef.current
    if (!ready || !k || !map) return
    const made: { setMap(m: any): void }[] = []
    const byMarker = new Map<any, (typeof points)[number]>()   // marker -> violation, so a cluster can report what it contains
    const mk = (p: (typeof points)[number], hi: boolean, dim: boolean) => {
      const s = hi ? 14 : 9
      const img = new k.maps.MarkerImage(svgUrl(hi ? '#dc2626' : dim ? '#94a3b8' : '#ea580c', s, hi), new k.maps.Size(s * 2 + 4, s * 2 + 4), { offset: new k.maps.Point(s + 2, s + 2) })
      const m = new k.maps.Marker({ position: new k.maps.LatLng(p.latitude, p.longitude), image: img, title: `${p.device_id} 위반 지점, ${formatKst(p.event_time)}`, zIndex: hi ? 10 : 1 })
      byMarker.set(m, p)
      k.maps.event.addListener(m, 'click', () => { setPinnedId(p.id); select.current(p.device_id) })
      k.maps.event.addListener(m, 'mouseover', () => setActive(String(p.id)))
      k.maps.event.addListener(m, 'mouseout', () => setActive(null))
      return m
    }
    const sel = selectedDeviceId ? points.filter((p) => p.device_id === selectedDeviceId) : []
    const rest = selectedDeviceId ? points.filter((p) => p.device_id !== selectedDeviceId) : points
    const clusterer = new k.maps.MarkerClusterer({
      map, averageCenter: true, minLevel: 4, markers: rest.map((p) => mk(p, false, !!selectedDeviceId)),
      calculator: [10, 50, 200], // 4 size classes
      texts: (n: number) => `위반 ${n}건`,
      styles: CLUSTER_STYLES,
    })
    const contents = (cluster: any) => (cluster.getMarkers() as any[]).map((m) => byMarker.get(m)).filter((x): x is (typeof points)[number] => !!x)
    k.maps.event.addListener(clusterer, 'clusterover', (cluster: any) => setHoverCluster(contents(cluster)))
    k.maps.event.addListener(clusterer, 'clusterout', () => setHoverCluster(null))
    k.maps.event.addListener(clusterer, 'clusterclick', () => setHoverCluster(null))
    const selMarkers = sel.map((p) => { const m = mk(p, true, false); m.setMap(map); return m })
    made.push(...selMarkers)

    for (let i = 1; i < routePts.length; i++) {
      const a = routePts[i - 1], b = routePts[i]
      const line = new k.maps.Polyline({
        path: [new k.maps.LatLng(a.latitude, a.longitude), new k.maps.LatLng(b.latitude, b.longitude)],
        strokeWeight: b.gap_before ? 3 : 5, strokeColor: b.gap_before ? '#94a3b8' : '#0891b2', strokeOpacity: 0.9, strokeStyle: b.gap_before ? 'shortdash' : 'solid',
      })
      line.setMap(map); made.push(line)
    }
    routePts.forEach((p, i) => {
      const end = i === 0 || i === routePts.length - 1
      const o = new k.maps.CustomOverlay({
        position: new k.maps.LatLng(p.latitude, p.longitude), yAnchor: 0.5, xAnchor: 0.5, zIndex: 5,
        content: `<div title="정기 위치 ${formatKst(p.recorded_at)}" style="width:${end ? 12 : 8}px;height:${end ? 12 : 8}px;border-radius:50%;border:2px solid #0891b2;background:${i === routePts.length - 1 ? '#0e7490' : '#fff'}"></div>`,
      })
      o.setMap(map); made.push(o)
    })

    // A selected device is framed on its own violations; otherwise everything (or the route) is in view.
    const basis = sel.length ? sel : points
    const focus = routePts.length ? routePts.map((p) => [p.latitude, p.longitude]) : basis.map((p) => [p.latitude, p.longitude])
    if (focus.length === 1) {
      map.setCenter(new k.maps.LatLng(focus[0][0], focus[0][1])); map.setLevel(3)
    } else if (focus.length) {
      const bounds = new k.maps.LatLngBounds()
      focus.forEach(([la, lo]) => bounds.extend(new k.maps.LatLng(la, lo)))
      map.setBounds(bounds, 48, 48, 48, 48)
    } else {
      map.setCenter(new k.maps.LatLng(INHA_ANCHOR.lat, INHA_ANCHOR.lon)); map.setLevel(5)
    }
    return () => { clusterer.clear(); made.forEach((m) => m.setMap(null)); setHoverCluster(null); setActive(null) }   // removed markers never fire mouseout
  }, [ready, points, selectedDeviceId, routePts])

  // "Show on map" from the list: centre on that violation and pin its card.
  useEffect(() => {
    const k = kRef.current, map = mapRef.current
    if (!ready || !k || !map || focusPointId == null) return
    const p = points.find((x) => x.id === focusPointId)
    if (!p) return
    map.setLevel(2); map.panTo(new k.maps.LatLng(p.latitude, p.longitude))
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reacting to an external map command, not derivable during render
    setPinnedId(p.id)
  }, [ready, focusPointId, points])

  const hovered = active ? points.find((p) => String(p.id) === active) ?? null : null
  const pinned = pinnedId != null ? points.find((p) => p.id === pinnedId) ?? null : null
  // A pinned card wins over hover previews, so it cannot flicker when the pointer crosses other markers (or rests on one after a scroll).
  const info: MapInfo | null = pinned ? { kind: 'point', point: pinned } : hoverCluster ? { kind: 'cluster', points: hoverCluster } : hovered ? { kind: 'point', point: hovered } : null
  const infoPinned = !!pinned
  return (
    <div className="relative w-full min-w-0 max-w-full overflow-hidden rounded-lg border border-slate-200 bg-[#eef3fa]" style={{ height }} data-testid="kakao-map">
      <div ref={box} className="size-full" role="application" aria-label={`위반 위치 지도 (카카오맵). 위반 지점 ${points.length}건`} />
      {!ready && <div className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm text-slate-500">지도를 불러오는 중…</div>}
      {ready && points.length === 0 && !routePts.length && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm text-slate-600"><span className="rounded bg-white/90 px-2 py-1 shadow-sm">표시할 GPS 위반 지점이 없습니다</span></div>
      )}
      {routePts.length >= 2 && (
        <div className="pointer-events-none absolute left-2 top-2 max-w-[75%] rounded-md bg-white/90 px-2 py-1 text-[11px] text-slate-600 shadow-sm">실선: 수집된 위치 연결 · 점선: 수집 공백 (실제 주행 경로와 다를 수 있음)</div>
      )}
      {info && (
        <MapInfoCard info={info} pinned={infoPinned} selectedDeviceId={selectedDeviceId} onSelectDevice={onSelectDevice} onClose={() => setPinnedId(null)} className="absolute bottom-6 left-2 z-10 max-w-[calc(100%-4.5rem)] sm:max-w-sm" />
      )}
    </div>
  )
}
