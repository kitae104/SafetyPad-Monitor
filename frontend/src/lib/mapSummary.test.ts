import { describe, expect, it } from 'vitest'
import type { MapEventPoint } from '../api/types'
import { groupByDevice, seoulToday, shiftDate, summarizeCluster, violationsOf } from './mapSummary'

const pt = (id: number, device_id: string, event_time: string): MapEventPoint => ({
  id, event_id: `e${id}`, device_id, alias: null, point_type: 'VIOLATION', event_time, received_at: event_time, latitude: 37.45, longitude: 126.65, simulated: false,
})

const P = [
  pt(1, 'PAD-0001', '2026-11-05T10:00:00+09:00'),
  pt(2, 'PAD-0002', '2026-11-05T12:00:00+09:00'),
  pt(3, 'PAD-0001', '2026-11-06T09:00:00+09:00'),
  pt(4, 'PAD-0003', '2026-11-07T08:00:00+09:00'),
  pt(5, 'PAD-0002', '2026-11-04T08:00:00+09:00'),
]

describe('mapSummary', () => {
  it('groups by device: most violations first, ties by most recent', () => {
    const g = groupByDevice(P)
    expect(g.map((x) => [x.device_id, x.count])).toEqual([['PAD-0001', 2], ['PAD-0002', 2], ['PAD-0003', 1]])
    expect(g[0].latest).toBe('2026-11-06T09:00:00+09:00')   // PAD-0001 is more recent than PAD-0002
  })

  it('summarizes a cluster: counts, devices, top list, time span', () => {
    const s = summarizeCluster(P, 2)
    expect(s).toMatchObject({ count: 5, deviceCount: 3, latest: '2026-11-07T08:00:00+09:00', earliest: '2026-11-04T08:00:00+09:00' })
    expect(s.top).toEqual([{ device_id: 'PAD-0001', count: 2 }, { device_id: 'PAD-0002', count: 2 }])
  })

  it('lists one device newest first', () => {
    expect(violationsOf(P, 'PAD-0002').map((p) => p.id)).toEqual([2, 5])
    expect(violationsOf(P, 'nope')).toEqual([])
  })

  it('computes the Seoul calendar day regardless of the machine time zone', () => {
    expect(seoulToday(new Date('2026-11-05T15:30:00Z'))).toBe('2026-11-06')   // 00:30 KST next day
    expect(seoulToday(new Date('2026-11-05T14:59:00Z'))).toBe('2026-11-05')
    expect(shiftDate('2026-03-01', -1)).toBe('2026-02-28')
    expect(shiftDate('2026-12-31', 1)).toBe('2027-01-01')
  })
})
