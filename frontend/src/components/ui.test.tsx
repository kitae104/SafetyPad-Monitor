import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BatteryGauge, ConfirmDialog, LastSeenIndicator, SummaryMetricCard } from './ui'

describe('ui components', () => {
  it('LastSeenIndicator says 최근 수신 and never online/offline', () => {
    const { container } = render(<LastSeenIndicator iso="2026-11-07T09:00:00+09:00" now={new Date('2026-11-07T12:00:00+09:00')} />)
    expect(container.textContent).toContain('최근 수신 · 3시간 전')
    expect(container.textContent).not.toMatch(/online|offline|온라인|오프라인/i)
  })
  it('BatteryGauge flags low battery with text, not colour alone', () => {
    render(<BatteryGauge level={17} />)
    expect(screen.getByText(/17%/).textContent).toContain('주의')
  })
  it('SummaryMetricCard exposes label and value', () => {
    render(<SummaryMetricCard label="오늘 위반 건수" value={37} />)
    expect(screen.getByRole('region', { name: '오늘 위반 건수' }).textContent).toContain('37')
  })
  it('ConfirmDialog is modal, closes on Escape and confirms by button', async () => {
    const onCancel = vi.fn()
    const onConfirm = vi.fn()
    render(<ConfirmDialog title="키 폐기" message="확인" confirmLabel="폐기" onConfirm={onConfirm} onCancel={onCancel} />)
    expect(screen.getByRole('dialog', { name: '키 폐기' }).getAttribute('aria-modal')).toBe('true')
    await userEvent.click(screen.getByRole('button', { name: '폐기' }))
    expect(onConfirm).toHaveBeenCalled()
    await userEvent.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalled()
  })
})
