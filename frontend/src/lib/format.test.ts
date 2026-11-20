import { formatGps, formatKst, formatLag, freshness, isoDate } from './format'

describe('format', () => {
  it('formats KST ISO strings without offset or fractions', () => {
    expect(formatKst('2026-11-05T23:30:00+09:00')).toBe('2026-11-05 23:30:00')
    expect(formatKst('2026-11-07T19:07:56.128386+09:00')).toBe('2026-11-07 19:07:56')
    expect(formatKst(null)).toBe('-')
  })
  it('formats lag and flags negative skew', () => {
    expect(formatLag(5)).toBe('5초')
    expect(formatLag(120)).toBe('2분')
    expect(formatLag(7200)).toBe('2.0시간')
    expect(formatLag(-600)).toBe('시계 오차 의심')
    expect(formatLag(-30)).toBe('0초')
  })
  it('never claims online: freshness only reports last-heard time', () => {
    const now = new Date('2026-11-07T12:00:00+09:00')
    expect(freshness('2026-11-07T11:59:30+09:00', now)).toEqual({ label: '방금 전', level: 'fresh' })
    expect(freshness('2026-11-07T09:00:00+09:00', now).label).toBe('3시간 전')
    expect(freshness('2026-11-05T09:00:00+09:00', now).level).toBe('stale')
    expect(freshness('2026-11-01T09:00:00+09:00', now).level).toBe('old')
    expect(freshness(null, now).level).toBe('none')
  })
  it('formats gps and dates', () => {
    expect(formatGps(null, null)).toBe('위치 없음')
    expect(formatGps(37.451448, 126.651542)).toBe('37.451448, 126.651542')
    expect(isoDate(new Date(2026, 10, 5))).toBe('2026-11-05')
  })
})
