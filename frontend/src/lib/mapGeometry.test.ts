import { clusterPoints, fitView, INHA_ANCHOR, project, scaleBar, unproject } from './mapGeometry'

describe('mapGeometry', () => {
  const W = 800, H = 440

  it('projects the view centre to the canvas centre and round-trips', () => {
    const view = { center: INHA_ANCHOR, metersPerPixel: 2 }
    expect(project(INHA_ANCHOR, view, W, H)).toEqual({ x: W / 2, y: H / 2 })
    const p = { lat: 37.455, lon: 126.655 }
    const { x, y } = project(p, view, W, H)
    const back = unproject(x, y, view, W, H)
    expect(back.lat).toBeCloseTo(p.lat, 6)
    expect(back.lon).toBeCloseTo(p.lon, 6)
  })

  it('puts north above and east to the right', () => {
    const view = { center: INHA_ANCHOR, metersPerPixel: 2 }
    expect(project({ lat: INHA_ANCHOR.lat + 0.001, lon: INHA_ANCHOR.lon }, view, W, H).y).toBeLessThan(H / 2)
    expect(project({ lat: INHA_ANCHOR.lat, lon: INHA_ANCHOR.lon + 0.001 }, view, W, H).x).toBeGreaterThan(W / 2)
  })

  it('fitView keeps every point inside the canvas', () => {
    const pts = [{ lat: 37.445, lon: 126.64 }, { lat: 37.458, lon: 126.662 }, { lat: 37.451, lon: 126.651 }]
    const view = fitView(pts, W, H)
    for (const p of pts) {
      const { x, y } = project(p, view, W, H)
      expect(x).toBeGreaterThanOrEqual(0); expect(x).toBeLessThanOrEqual(W)
      expect(y).toBeGreaterThanOrEqual(0); expect(y).toBeLessThanOrEqual(H)
    }
  })

  it('fitView on no points falls back to the Inha area and a single point does not over-zoom', () => {
    expect(fitView([], W, H).center.lat).toBeCloseTo(INHA_ANCHOR.lat, 3)
    const one = fitView([{ lat: 37.45, lon: 126.65 }], W, H)
    expect(one.metersPerPixel).toBeGreaterThan(0.4)
    expect(Number.isFinite(one.metersPerPixel)).toBe(true)
  })

  it('clusters near points and keeps far points separate', () => {
    const items = [{ x: 10, y: 10, id: 1 }, { x: 20, y: 15, id: 2 }, { x: 300, y: 300, id: 3 }]
    const c = clusterPoints(items, 48)
    expect(c).toHaveLength(2)
    expect(c.find((k) => k.items.length === 2)!.x).toBe(15)
    expect(clusterPoints(items, 1)).toHaveLength(3)
  })

  it('never leaves two markers closer than the radius (no marker hidden under a cluster)', () => {
    let seed = 7
    const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647)
    const items = Array.from({ length: 400 }, () => ({ x: rnd() * 800, y: rnd() * 440 }))
    const radius = 34
    const out = clusterPoints(items, radius)
    expect(out.reduce((n, c) => n + c.items.length, 0)).toBe(400)   // nothing lost
    for (let i = 0; i < out.length; i++)
      for (let j = i + 1; j < out.length; j++)
        expect(Math.hypot(out[i].x - out[j].x, out[i].y - out[j].y)).toBeGreaterThanOrEqual(radius - 1e-9)
  })

  it('scale bar uses round numbers that fit', () => {
    for (const mpp of [0.7, 3, 17, 120]) {
      const s = scaleBar(mpp)
      expect(s.px).toBeLessThanOrEqual(120 + 1e-9)
      expect([1, 2, 5]).toContain(Number((s.meters / Math.pow(10, Math.floor(Math.log10(s.meters)))).toFixed(6)))
    }
    expect(scaleBar(10).label).toBe('1 km')
  })
})
