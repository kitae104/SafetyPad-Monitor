import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HEARTBEAT_MS, LIVE_PATH, LiveDataProvider, useLiveData } from './liveData'

const apiMock = vi.fn()
let demoTools = true

vi.mock('../api/client', () => ({
  api: (...a: unknown[]) => apiMock(...a),
  ApiError: class ApiError extends Error {},
}))
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ config: { demo_tools: demoTools } }),
}))

function Probe() {
  const l = useLiveData()
  return (
    <>
      <button onClick={() => void l.toggle()}>toggle</button>
      <output data-testid="s">{JSON.stringify({ a: l.available, r: l.running, g: l.generated, t: l.tick })}</output>
    </>
  )
}
const state = () => JSON.parse(screen.getByTestId('s').textContent ?? '{}') as { a: boolean; r: boolean; g: number; t: number }
const posts = () => apiMock.mock.calls.filter((c) => c[1]?.method === 'POST').map((c) => c[1].body.enabled)

describe('LiveDataProvider (demo-only random data generator)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    apiMock.mockReset()
    apiMock.mockImplementation(async (_p: string, o?: { method?: string; body?: { enabled: boolean } }) =>
      o?.method === 'POST' ? { running: o.body!.enabled, generated: 7 } : { running: false, generated: 0 })
    demoTools = true
  })
  afterEach(() => vi.useRealTimers())

  it('does nothing at all when the server does not offer demo tools (production)', async () => {
    demoTools = false
    render(<LiveDataProvider><Probe /></LiveDataProvider>)
    await act(async () => { await vi.advanceTimersByTimeAsync(HEARTBEAT_MS * 3) })
    expect(state().a).toBe(false)
    expect(apiMock).not.toHaveBeenCalled()
  })

  it('generates only while pressed: renews the lease and ticks every heartbeat, stops when pressed again', async () => {
    render(<LiveDataProvider><Probe /></LiveDataProvider>)
    await act(async () => {})
    expect(state()).toMatchObject({ a: true, r: false })
    expect(posts()).toEqual([])                                                    // nothing is generated until pressed

    await act(async () => { screen.getByText('toggle').click() })
    expect(posts()).toEqual([true])
    expect(state()).toMatchObject({ r: true, g: 7 })
    const t0 = state().t

    await act(async () => { await vi.advanceTimersByTimeAsync(HEARTBEAT_MS * 2) })
    expect(posts()).toEqual([true, true, true])                                    // two heartbeats = two renewals
    expect(state().t).toBe(t0 + 2)                                                 // screens are told to refetch each time

    await act(async () => { screen.getByText('toggle').click() })
    expect(posts()).toEqual([true, true, true, false])
    expect(state().r).toBe(false)
    const calls = apiMock.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(HEARTBEAT_MS * 4) })
    expect(apiMock.mock.calls.length).toBe(calls)                                  // no heartbeats once released
  })

  it('adopts a run that is already in progress on the server', async () => {
    apiMock.mockImplementation(async (_p: string, o?: { method?: string }) => (o?.method === 'POST' ? { running: true, generated: 9 } : { running: true, generated: 4 }))
    render(<LiveDataProvider><Probe /></LiveDataProvider>)
    await act(async () => {})
    expect(state()).toMatchObject({ r: true, g: 4 })
    expect(apiMock).toHaveBeenCalledWith(LIVE_PATH)
  })

  it('releases the button when a renewal fails (e.g. session ended)', async () => {
    render(<LiveDataProvider><Probe /></LiveDataProvider>)
    await act(async () => {})
    await act(async () => { screen.getByText('toggle').click() })
    apiMock.mockRejectedValue(new Error('down'))
    await act(async () => { await vi.advanceTimersByTimeAsync(HEARTBEAT_MS) })
    expect(state().r).toBe(false)
  })
})
