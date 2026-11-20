import { csvExportUrl, qs, readCookie } from './client'

describe('api client helpers', () => {
  it('reads the CSRF cookie by exact name', () => {
    expect(readCookie('XSRF-TOKEN', 'a=1; XSRF-TOKEN=abc%3D; b=2')).toBe('abc=')
    expect(readCookie('XSRF', 'XSRF-TOKEN=x')).toBeNull()
  })
  it('drops empty query values and encodes the rest', () => {
    expect(qs({ a: '', b: undefined, c: 'PAD 1', d: 0 })).toBe('?c=PAD+1&d=0')
    expect(qs({})).toBe('')
  })
  it('builds a same-origin csv export url carrying the active filters only', () => {
    expect(csvExportUrl({ from: '2026-11-01', device_id: 'PAD-1', page: 3, size: 20, sort: 'x' })).toBe(
      '/api/v1/admin/events/export.csv?from=2026-11-01&device_id=PAD-1',
    )
  })
})
