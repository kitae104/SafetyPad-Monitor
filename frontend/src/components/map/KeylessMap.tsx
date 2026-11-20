import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Minus, Plus, RotateCcw } from 'lucide-react'
import type { MapEventPoint, RouteResponse } from '../../api/types'
import { clampMpp, clusterPoints, fitView, INHA_ANCHOR, project, scaleBar, type View } from '../../lib/mapGeometry'
import { formatKst } from '../../lib/format'
import { MapInfoCard, type MapInfo } from './MapInfoCard'

export interface MapViewProps {
  points: MapEventPoint[]
  selectedDeviceId: string | null
  onSelectDevice: (id: string | null) => void
  route?: RouteResponse | null
  height?: number
  /** Violation point the user asked to see (from the list beside the map): the map centres on it and pins its details. */
  focusPointId?: number | null
}

const CLUSTER_RADIUS = 46 // > half the widest cluster pill (~38) + marker half-size (8): a single marker never hides under a cluster
const clusterW = (n: number) => 46 + String(n).length * 8   // pill width for "위반 N건"

function useSize(ref: React.RefObject<HTMLDivElement | null>, fallbackH: number) {
  const [size, setSize] = useState({ w: 640, h: fallbackH })
  useEffect(() => {
    const el = ref.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(([e]) => {
      const w = Math.round(e.contentRect.width)
      if (w > 0) setSize((s) => (s.w === w ? s : { w, h: fallbackH }))
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [ref, fallbackH])
  return size
}

/**
 * Key-less fallback map: no tiles, no external requests. It draws a labelled reference grid, the Inha anchor,
 * clustered violation markers and (optionally) a straight-segment route through real GPS pings.
 * Real map SDKs plug in behind the same MapViewProps contract (see ViolationMap).
 */
export function KeylessMap({ points, selectedDeviceId, onSelectDevice, route, height = 400, focusPointId = null }: MapViewProps) {
  const box = useRef<HTMLDivElement>(null)
  const { w, h } = useSize(box, height)
  const [userView, setUserView] = useState<View | null>(null)
  const drag = useRef<{ x: number; y: number; view: View } | null>(null)
  const [active, setActive] = useState<string | null>(null)
  const [hoverKey, setHoverKey] = useState<{ first: number; n: number } | null>(null)   // identifies the hovered badge; a badge that vanished (zoom, selection) must not leave a stale card
  const [pinnedId, setPinnedId] = useState<number | null>(null)
  const [seenFocus, setSeenFocus] = useState<number | null>(null)

  const routePts = useMemo(() => (route?.sufficient ? route.points : []), [route])
  const fitted = useMemo(() => {
    const own = selectedDeviceId ? points.filter((p) => p.device_id === selectedDeviceId) : []   // a selected device is framed on its own points
    const basis = routePts.length ? routePts.map((p) => ({ lat: p.latitude, lon: p.longitude })) : (own.length ? own : points).map((p) => ({ lat: p.latitude, lon: p.longitude }))
    return fitView(basis, w, h)
  }, [points, routePts, selectedDeviceId, w, h])
  const view = userView ?? fitted

  // "Show on map" from the list: centre on that point and pin its card. Adjusting state while rendering (not in an effect) avoids a flash.
  if (focusPointId !== seenFocus) {
    setSeenFocus(focusPointId)
    const fp = focusPointId != null ? points.find((p) => p.id === focusPointId) : null
    if (fp) {
      setUserView({ center: { lat: fp.latitude, lon: fp.longitude }, metersPerPixel: 3 })
      setPinnedId(fp.id)
    }
  }

  const zoom = useCallback((factor: number, at?: { lat: number; lon: number }) => {
    setUserView({ center: at ?? view.center, metersPerPixel: clampMpp(view.metersPerPixel * factor) })
  }, [view])

  const projected = useMemo(() => points.map((p) => ({ p, ...project({ lat: p.latitude, lon: p.longitude }, view, w, h) })), [points, view, w, h])
  const inView = projected.filter((q) => q.x > -20 && q.x < w + 20 && q.y > -20 && q.y < h + 20)
  const selected = selectedDeviceId ? inView.filter((q) => q.p.device_id === selectedDeviceId) : []
  const others = selectedDeviceId ? inView.filter((q) => q.p.device_id !== selectedDeviceId) : inView
  const clusters = clusterPoints(others, CLUSTER_RADIUS) // cheap (<= 5,000 points) and `others` changes every render anyway
  const sb = scaleBar(view.metersPerPixel)
  const anchor = project(INHA_ANCHOR, view, w, h)
  const grid = useMemo(() => {
    const step = sb.px
    const lines: { x?: number; y?: number }[] = []
    if (step > 8) {
      for (let x = ((w / 2 - anchor.x) % step + step) % step; x < w; x += step) lines.push({ x })
      for (let y = ((h / 2 - anchor.y) % step + step) % step; y < h; y += step) lines.push({ y })
    }
    return lines
  }, [sb.px, anchor.x, anchor.y, w, h])
  const toXY = (lat: number, lon: number) => project({ lat, lon }, view, w, h)

  const onKey = (e: React.KeyboardEvent) => {
    const d = 60 * view.metersPerPixel
    const mLat = d / 111320
    const map: Record<string, [number, number]> = { ArrowUp: [mLat, 0], ArrowDown: [-mLat, 0], ArrowLeft: [0, -mLat], ArrowRight: [0, mLat] }
    if (e.key === '+' || e.key === '=') zoom(1 / 1.6)
    else if (e.key === '-') zoom(1.6)
    else if (map[e.key]) {
      e.preventDefault()
      setUserView({ center: { lat: view.center.lat + map[e.key][0], lon: view.center.lon + map[e.key][1] }, metersPerPixel: view.metersPerPixel })
    }
  }

  const label = (q: (typeof projected)[number]) => `${q.p.device_id} 위반 지점, ${formatKst(q.p.event_time)}`
  const pinned = pinnedId != null ? points.find((p) => p.id === pinnedId) ?? null : null
  const hovered = active ? [...selected, ...clusters.filter((c) => c.items.length === 1).flatMap((c) => c.items)].find((q) => String(q.p.id) === active)?.p ?? null : null   // only markers actually drawn
  const hoverCluster = hoverKey ? clusters.find((c) => c.items.length === hoverKey.n && c.items[0].p.id === hoverKey.first)?.items.map((i) => i.p) ?? null : null
  // A pinned card wins over hover previews, so it cannot flicker when the pointer crosses other markers (or rests on one after a scroll).
  const info: MapInfo | null = pinned ? { kind: 'point', point: pinned } : hoverCluster ? { kind: 'cluster', points: hoverCluster } : hovered ? { kind: 'point', point: hovered } : null
  const infoPinned = !!pinned

  return (
    <div ref={box} className="relative w-full min-w-0 max-w-full select-none overflow-hidden rounded-lg border border-slate-200 bg-[#eef3fa]" style={{ height }} data-testid="keyless-map">
      <svg
        width={w}
        height={h}
        role="group"
        aria-label={`위반 위치 지도 (키 없는 대체 지도). 위반 지점 ${points.length}건. 방향키로 이동, + − 로 확대 축소`}
        tabIndex={0}
        onKeyDown={onKey}
        className="block max-w-full touch-none cursor-grab active:cursor-grabbing"
        onPointerDown={(e) => {
          // Do NOT capture the pointer here: capturing retargets the later `click` to the svg and
          // would swallow clicks on markers/clusters. Capture only once a real drag starts.
          drag.current = { x: e.clientX, y: e.clientY, view }
        }}
        onPointerMove={(e) => {
          const d = drag.current
          if (!d) return
          const dx = e.clientX - d.x
          const dy = e.clientY - d.y
          if (Math.abs(dx) + Math.abs(dy) < 4) return
          if (!(e.currentTarget as SVGSVGElement).hasPointerCapture(e.pointerId)) {
            ;(e.currentTarget as SVGSVGElement).setPointerCapture(e.pointerId)
          }
          const mpp = d.view.metersPerPixel
          setUserView({
            center: { lat: d.view.center.lat + (dy * mpp) / 111320, lon: d.view.center.lon - (dx * mpp) / (111320 * Math.cos((d.view.center.lat * Math.PI) / 180)) },
            metersPerPixel: mpp,
          })
        }}
        onPointerUp={() => (drag.current = null)}
        onPointerCancel={() => (drag.current = null)}
      >
        {grid.map((g, i) => (g.x !== undefined ? <line key={i} x1={g.x} x2={g.x} y1={0} y2={h} stroke="#d5deec" strokeWidth="1" /> : <line key={i} y1={g.y} y2={g.y} x1={0} x2={w} stroke="#d5deec" strokeWidth="1" />))}

        {/* reference landmark: approximate Inha Technical College anchor, not a surveyed position */}
        <g transform={`translate(${anchor.x},${anchor.y})`} aria-hidden="true">
          <circle r="5" fill="none" stroke="#64748b" strokeWidth="1.5" />
          <line x1="-9" x2="9" y1="0" y2="0" stroke="#64748b" /><line y1="-9" y2="9" x1="0" x2="0" stroke="#64748b" />
          <text x="10" y="-8" fontSize="11" fill="#475569">인하공전(기준점)</text>
        </g>

        {routePts.length >= 2 && (
          <g data-testid="route-layer" aria-label="수집 지점 간 연결선">
            {routePts.slice(1).map((p, i) => {
              const a = toXY(routePts[i].latitude, routePts[i].longitude)
              const b = toXY(p.latitude, p.longitude)
              return <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke={p.gap_before ? '#94a3b8' : '#0891b2'} strokeWidth={p.gap_before ? 2 : 3} strokeDasharray={p.gap_before ? '6 6' : undefined} strokeLinecap="round" />
            })}
            {routePts.map((p, i) => {
              const q = toXY(p.latitude, p.longitude)
              return <circle key={i} cx={q.x} cy={q.y} r={i === 0 || i === routePts.length - 1 ? 5 : 3} fill={i === routePts.length - 1 ? '#0e7490' : '#fff'} stroke="#0891b2" strokeWidth="2"><title>{`정기 위치 ${formatKst(p.recorded_at)}`}</title></circle>
            })}
            {(() => { const s = toXY(routePts[0].latitude, routePts[0].longitude); const e = toXY(routePts[routePts.length - 1].latitude, routePts[routePts.length - 1].longitude); return <><text x={s.x + 7} y={s.y - 7} fontSize="11" fill="#0e7490">시작</text><text x={e.x + 7} y={e.y - 7} fontSize="11" fill="#0e7490">마지막 수집</text></> })()}
          </g>
        )}

        {/* other devices: clustered (dimmed when a device is selected) */}
        <g opacity={selectedDeviceId ? 0.35 : 1}>
          {clusters.map((c, i) =>
            c.items.length > 1 ? (
              <g key={i} transform={`translate(${c.x},${c.y})`} role="button" tabIndex={0} aria-label={`위반 ${c.items.length}건 묶음, 장비 ${new Set(c.items.map((i) => i.p.device_id)).size}대. 확대하려면 선택`} data-testid="map-cluster" className="cursor-pointer"
                onMouseEnter={() => setHoverKey({ first: c.items[0].p.id, n: c.items.length })} onMouseLeave={() => setHoverKey(null)} onFocus={() => setHoverKey({ first: c.items[0].p.id, n: c.items.length })} onBlur={() => setHoverKey(null)}
                onClick={(e) => { e.stopPropagation(); setHoverKey(null); setUserView({ center: { lat: view.center.lat - ((c.y - h / 2) * view.metersPerPixel) / 111320, lon: view.center.lon + ((c.x - w / 2) * view.metersPerPixel) / (111320 * Math.cos((view.center.lat * Math.PI) / 180)) }, metersPerPixel: clampMpp(view.metersPerPixel / 2.2) }) }}
                onKeyDown={(e) => e.key === 'Enter' && (e.currentTarget as SVGGElement).dispatchEvent(new MouseEvent('click', { bubbles: true }))}>
                <rect x={-clusterW(c.items.length) / 2} y={-15} width={clusterW(c.items.length)} height={30} rx={15} fill="#1d4ed8" stroke="#fff" strokeWidth="2" />
                <text textAnchor="middle" dy="4" fontSize="12" fontWeight="700" fill="#fff">{`위반 ${c.items.length}건`}</text>
              </g>
            ) : (
              <Marker key={c.items[0].p.id} q={c.items[0]} label={label(c.items[0])} dim={!!selectedDeviceId} onSelect={() => { setPinnedId(c.items[0].p.id); onSelectDevice(c.items[0].p.device_id) }} onHover={setActive} />
            ),
          )}
        </g>
        {/* selected device: never clustered, always highlighted */}
        {selected.map((q) => <Marker key={q.p.id} q={q} label={label(q)} highlight onSelect={() => { setPinnedId(q.p.id); onSelectDevice(q.p.device_id) }} onHover={setActive} />)}

        <g transform={`translate(12,${h - 16})`} aria-hidden="true">
          <line x2={sb.px} stroke="#334155" strokeWidth="2" /><line y1="-4" y2="4" stroke="#334155" strokeWidth="2" /><line x1={sb.px} x2={sb.px} y1="-4" y2="4" stroke="#334155" strokeWidth="2" />
          <text x={sb.px + 8} y="4" fontSize="11" fill="#334155">{sb.label}</text>
        </g>
      </svg>

      <div className="pointer-events-none absolute left-2 top-2 max-w-[75%] rounded-md bg-white/90 px-2 py-1 text-[11px] text-slate-600 shadow-sm">
        키 없는 대체 지도 — 도로·건물 없음, 위치는 근사치
      </div>
      <div className="absolute right-2 top-2 flex flex-col gap-1">
        <button type="button" aria-label="확대" onClick={() => zoom(1 / 1.6)} className="inline-flex size-11 items-center justify-center rounded-md border border-slate-300 bg-white shadow-sm hover:bg-slate-50"><Plus size={18} aria-hidden="true" /></button>
        <button type="button" aria-label="축소" onClick={() => zoom(1.6)} className="inline-flex size-11 items-center justify-center rounded-md border border-slate-300 bg-white shadow-sm hover:bg-slate-50"><Minus size={18} aria-hidden="true" /></button>
        <button type="button" aria-label="전체 보기" onClick={() => setUserView(null)} className="inline-flex size-11 items-center justify-center rounded-md border border-slate-300 bg-white shadow-sm hover:bg-slate-50"><RotateCcw size={16} aria-hidden="true" /></button>
      </div>
      {info && (
        <MapInfoCard info={info} pinned={infoPinned} selectedDeviceId={selectedDeviceId} onSelectDevice={onSelectDevice} onClose={() => setPinnedId(null)} className="absolute bottom-9 left-2 max-w-[calc(100%-4.5rem)] sm:max-w-sm" />
      )}
      {points.length === 0 && !routePts.length && (
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-1 px-4 text-center text-sm text-slate-500">
          <span>표시할 GPS 위반 지점이 없습니다</span>
          <span className="text-xs">운영 환경에는 가상 데이터가 없으며, GPS 좌표가 포함된 실제 위반 이벤트가 수신되면 표시됩니다.</span>
        </div>
      )}
    </div>
  )
}

/** Violation marker: diamond with "!" (shape, not just colour, distinguishes it from periodic pings). */
function Marker({ q, label, onSelect, onHover, highlight = false, dim = false }: {
  q: { p: MapEventPoint; x: number; y: number }; label: string; onSelect: () => void; onHover: (id: string | null) => void; highlight?: boolean; dim?: boolean
}) {
  const s = highlight ? 11 : 8
  return (
    <g transform={`translate(${q.x},${q.y})`} role="button" tabIndex={0} aria-label={label} data-testid={highlight ? 'map-marker-selected' : 'map-marker'} className="cursor-pointer"
      onClick={(e) => { e.stopPropagation(); onSelect() }} onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect()}
      onMouseEnter={() => onHover(String(q.p.id))} onMouseLeave={() => onHover(null)} onFocus={() => onHover(String(q.p.id))} onBlur={() => onHover(null)}>
      <polygon points={`0,${-s} ${s},0 0,${s} ${-s},0`} fill={highlight ? '#dc2626' : dim ? '#94a3b8' : '#ea580c'} stroke="#fff" strokeWidth="2" />
      {highlight && <text textAnchor="middle" dy="4" fontSize="11" fontWeight="800" fill="#fff">!</text>}
      <title>{label}</title>
    </g>
  )
}
