import { expect, test } from '@playwright/test'
import { clickSingleMarker, deactivateTestDevices, expectNoHorizontalScroll, goTo, login, PASSWORD, trackTestDevice, USER } from './helpers'

// Disclaimers such as '온라인 여부 아님' are fine; what must never appear is a claim like '온라인 장비 12대'.
const ONLINE_CLAIM = /(온라인|오프라인)\s*(장비|상태|중|\d)/

const shot = (name: string, project: string) => `../docs/screenshots/${project.split('-')[1]}-${name}.png`

test.afterAll(async ({ request }) => { await deactivateTestDevices(request) })

test.beforeAll(async ({ request }) => {
  // Seed fictional data through the real admin API (dev profile only).
  await request.get('http://localhost:5173/api/v1/admin/csrf')
  const cookies = (await request.storageState()).cookies
  const xsrf = cookies.find((c) => c.name === 'XSRF-TOKEN')!.value
  const headers = { 'X-XSRF-TOKEN': xsrf, 'Content-Type': 'application/json' }
  const l = await request.post('http://localhost:5173/api/v1/admin/login', { headers, data: { username: USER, password: PASSWORD } })
  expect(l.status()).toBe(200)
  const xsrf2 = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')!.value
  const imp = await request.post('http://localhost:5173/api/v1/admin/dev/import-demo', { headers: { ...headers, 'X-XSRF-TOKEN': xsrf2 }, data: {} })
  expect(imp.status()).toBe(200)
})

test('login rejects wrong password with a generic message, then succeeds', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('아이디').fill(USER)
  await page.getByLabel('비밀번호').fill('definitely-wrong-password')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.getByRole('alert')).toContainText('아이디 또는 비밀번호가 올바르지 않습니다')
  await expectNoHorizontalScroll(page)
  await login(page)
})

test('unauthenticated visitors never see dashboard data', async ({ page }) => {
  await page.goto('/events')
  await expect(page.getByRole('heading', { name: '관리자 로그인' })).toBeVisible()
  await expect(page.getByTestId('kpi-grid')).toHaveCount(0)
})

test('dashboard shows KPIs, 가상 데이터 labels, honest wording and fits the viewport', async ({ page }, info) => {
  await login(page)
  // Other scenarios register extra devices, so assert "at least the 50 seeded devices".
  await expect.poll(async () => Number((await page.getByTestId('kpi-devices').innerText()).match(/\d+/)?.[0] ?? 0)).toBeGreaterThanOrEqual(50)
  await expect(page.getByTestId('kpi-today')).not.toContainText('–')
  await expect(page.getByTestId('kpi-received')).toContainText('온라인 여부 아님')
  await expect(page.getByTestId('demo-badge').first()).toBeVisible()
  await expect(page.getByText('가상 데이터 —')).toBeVisible()
  await expect(page.getByText(ONLINE_CLAIM)).toHaveCount(0)
  await expect(page.getByRole('img', { name: /최근 7일 일자별 위반 건수/ })).toBeVisible()
  await expectNoHorizontalScroll(page)
  // desktop shows a table, narrow screens show stacked cards
  if (info.project.name.startsWith('mobile')) {
    await expect(page.getByTestId('event-card').first()).toBeVisible()
    await expect(page.getByTestId('event-table')).toBeHidden()
  } else {
    await expect(page.getByTestId('event-table')).toBeVisible()
  }
  await page.screenshot({ path: shot('dashboard', info.project.name), fullPage: true })
})

test('every navigation entry works and pages do not overflow', async ({ page }, info) => {
  await login(page)
  for (const [label, heading] of [
    ['위반 이벤트', '위반 이벤트'], ['장비 관리', '장비 관리'], ['위치·경로', '위치·경로'], ['통계 리포트', '통계 리포트'], ['운영 설정', '운영 설정'], ['대시보드', '대시보드'],
  ] as const) {
    await goTo(page, label)
    await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible()
    await expectNoHorizontalScroll(page)
  }
  await goTo(page, '통계 리포트')
  await page.screenshot({ path: shot('reports', info.project.name), fullPage: true })
  await goTo(page, '운영 설정')
  await expect(page.getByText('MQTT 수신')).toBeVisible()          // shows whether the MQTT listener is on, never secrets
  await expect(page.getByTestId('demo-tools')).toBeVisible()
  await page.screenshot({ path: shot('ops', info.project.name), fullPage: true })
})

test('events: filter by device, KST times, detail dialog, CSV with BOM and neutralised cells', async ({ page }, info) => {
  await login(page)
  await goTo(page, '위반 이벤트')
  await expect(page.getByTestId('result-count')).toBeVisible()
  const all = await page.getByTestId('result-count').innerText()

  await page.getByRole('searchbox').or(page.getByPlaceholder('예: PAD-0001')).first().fill('PAD-0007')
  await page.getByRole('button', { name: '검색' }).click()
  await expect(page.getByTestId('result-count')).not.toHaveText(all)
  const rows = info.project.name.startsWith('mobile') ? page.getByTestId('event-card') : page.getByTestId('event-table').locator('tbody tr')
  const n = await rows.count()
  expect(n).toBeGreaterThan(0)
  for (let i = 0; i < n; i++) await expect(rows.nth(i)).toContainText('PAD-0007')
  await expectNoHorizontalScroll(page)
  await page.screenshot({ path: shot('events', info.project.name), fullPage: true })

  // detail dialog (keyboard: Esc closes)
  const first = info.project.name.startsWith('mobile') ? rows.first() : page.getByRole('button', { name: /PAD-0007 이벤트 상세 보기/ }).first()
  await first.click()
  const dlg = page.getByRole('dialog', { name: '이벤트 상세' })
  await expect(dlg).toContainText('최초 수신 (서버 기록)')
  await expect(dlg).toContainText('가상 데이터')
  await page.keyboard.press('Escape')
  await expect(dlg).toBeHidden()

  // CSV: same session cookie, BOM, KST
  const href = await page.getByTestId('csv-export').getAttribute('href')
  const res = await page.request.get(href!)
  expect(res.status()).toBe(200)
  const body = await res.text()
  expect(body.startsWith('﻿"event_id"')).toBe(true)
  expect(body).toContain('+09:00')
  expect(body).toContain('가상 데이터')
})

test('events: GPS-less events stay in the list and can be filtered', async ({ page }) => {
  await login(page)
  await page.goto('/events?has_gps=false')
  await expect(page.getByTestId('result-count')).toBeVisible()
  await expect(page.locator('span:visible', { hasText: '위치 없음' }).first()).toBeVisible()
})

test('devices: panel, key issued once and never shown again, then logout', async ({ page }, info) => {
  await login(page)
  await goTo(page, '장비 관리')
  await expect(page.locator('span:visible', { hasText: /최근 수신 ·/ }).first()).toBeVisible()
  await expect(page.getByText(ONLINE_CLAIM)).toHaveCount(0)
  await expectNoHorizontalScroll(page)
  await page.screenshot({ path: shot('devices', info.project.name), fullPage: true })

  const id = `E2E-${info.project.name.slice(-4)}-${Date.now().toString().slice(-6)}`
  trackTestDevice(id)
  await page.getByRole('button', { name: '장비 등록' }).click()
  await page.getByLabel('장비 ID').fill(id)
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(page.getByTestId('device-admin-panel')).toContainText(id)
  await expect(page.getByTestId('admin-last-violation')).toContainText('GPS가 있는 이벤트 없음') // new device: nothing invented

  await page.getByRole('button', { name: '새 키 발급' }).click()
  await page.getByRole('button', { name: '발급', exact: true }).click()
  const key = await page.getByTestId('new-api-key').inputValue()
  expect(key).toMatch(/^spk_/)
  await page.getByRole('button', { name: '확인했습니다' }).click()

  await page.reload()
  await goTo(page, '장비 관리')
  await page.getByPlaceholder('예: PAD-0001').fill(id)
  await page.getByRole('button', { name: '검색' }).click()
  await page.getByRole('button', { name: id }).first().click()
  await expect(page.getByTestId('device-admin-panel')).toContainText(key.slice(0, 8))
  expect(await page.content()).not.toContain(key)

  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('heading', { name: '관리자 로그인' })).toBeVisible()
})

test('devices: admin panel shows the last violation point with a not-live-position disclaimer', async ({ page }) => {
  await login(page)
  await page.goto('/devices?q=PAD-0007')
  await page.getByRole('button', { name: 'PAD-0007' }).first().click()
  const box = page.getByTestId('admin-last-violation')
  await expect(box).toContainText('실시간 위치 아님')
  await expect(box).toContainText(/\d+\.\d{6}, \d+\.\d{6}/)
  await expect(box).toContainText('기록')
})

test('dashboard: tapping a map marker brings the selected-device panel into view and clearing returns to the map', async ({ page }) => {
  await login(page)
  await expect(page.getByTestId('keyless-map')).toBeVisible()
  await expect(page.getByRole('region', { name: '선택 장비' })).toHaveCount(0)                  // nothing selected: the map has the row to itself
  const wide = (await page.getByTestId('keyless-map').boundingBox())!.width
  await clickSingleMarker(page)
  await expect(page.getByTestId('device-panel')).toBeVisible()
  if ((page.viewportSize()?.width ?? 0) >= 1024) expect((await page.getByTestId('keyless-map').boundingBox())!.width).toBeLessThan(wide)   // side by side: the map narrows to make room
  await expect(page.getByRole('region', { name: '선택 장비' })).toBeInViewport({ ratio: 0.9 })   // the whole panel, not just its top edge
  await expectNoHorizontalScroll(page)

  await page.getByRole('button', { name: '선택 해제' }).click()
  await expect(page.getByTestId('device-panel')).toHaveCount(0)
  await expect(page.getByRole('region', { name: '선택 장비' })).toHaveCount(0)
  await expect(page.getByTestId('keyless-map')).toBeInViewport()                                // back at the map
})

test('devices: choosing a device brings its panel into view, marks the selection and returns on close', async ({ page }) => {
  await login(page)
  await goTo(page, '장비 관리')
  // the last device of the page: on stacked layouts the panel is far below it
  const last = page.locator('[data-testid=device-table] tbody button:visible, ul[aria-label="장비 카드 목록"] button:visible').last()
  await last.scrollIntoViewIfNeeded()
  const id = (await last.evaluate((el) => (el.querySelector('span') ?? el).textContent ?? '')).trim()   // cards: ID is the first span
  await last.click()
  await expect(page.getByTestId('device-admin-panel')).toContainText(id)
  await expect(page.getByTestId('device-admin-panel')).toBeInViewport()               // not left below the fold
  await expect(last).toHaveAttribute('aria-pressed', 'true')                           // the selection is visible and exposed
  await expectNoHorizontalScroll(page)

  await page.getByRole('button', { name: '닫기' }).click()
  await expect(page.getByTestId('device-admin-panel')).toHaveCount(0)
  await expect(last).toBeInViewport()                                                  // back at the device that was chosen
  await expect(last).toHaveAttribute('aria-pressed', 'false')
})

test('devices: "비활성 장비 숨기기" filter hides inactive devices, survives search and reload', async ({ page }, info) => {
  await login(page)
  await goTo(page, '장비 관리')
  const id = `E2E-${info.project.name.slice(-4)}-H${Date.now().toString().slice(-6)}`
  trackTestDevice(id)
  await page.getByRole('button', { name: '장비 등록' }).click()
  await page.getByLabel('장비 ID').fill(id)
  await page.getByRole('button', { name: '등록', exact: true }).click()
  await expect(page.getByTestId('device-admin-panel')).toContainText(id)
  await page.getByRole('button', { name: '비활성화', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '비활성화', exact: true }).click()
  await expect(page.getByTestId('device-admin-panel')).toContainText('비활성')

  await page.getByPlaceholder('예: PAD-0001').fill(id)
  await page.getByRole('button', { name: '검색' }).click()
  await expect(page.getByRole('button', { name: id }).first()).toBeVisible()          // default: inactive devices are listed

  await page.getByTestId('hide-inactive').check()
  await expect(page.getByText('표시할 활성 장비가 없습니다')).toBeVisible()
  await expect(page.getByRole('button', { name: id })).toHaveCount(0)
  await expect(page).toHaveURL(/hide_inactive=1/)
  await expect(page).toHaveURL(new RegExp(`q=${id}`))                                   // search term kept

  await page.reload()
  await expect(page.getByTestId('hide-inactive')).toBeChecked()                         // filter survives a reload
  await expect(page.getByText('표시할 활성 장비가 없습니다')).toBeVisible()

  await page.getByTestId('hide-inactive').uncheck()
  await expect(page.getByRole('button', { name: id }).first()).toBeVisible()
  await expectNoHorizontalScroll(page)
})

test('live generator: data arrives only while the button is pressed, screens refresh by themselves', async ({ page }) => {
  test.setTimeout(90_000)
  await login(page)
  const btn = page.getByTestId('live-generator')
  const total = async () => ((await (await page.request.get('/api/v1/admin/events?size=1')).json()) as { total: number }).total
  const todayKpi = async () => Number((await page.getByTestId('kpi-today').locator('p.text-3xl').textContent())?.match(/\d+/)?.[0] ?? NaN)

  await expect(btn).toHaveAttribute('aria-pressed', 'false')
  await expect(page.getByTestId('kpi-today')).toContainText(/\d/)
  const idle = await total()
  await page.waitForTimeout(3000)
  expect(await total(), 'nothing is generated until the button is pressed').toBe(idle)
  const kpiBefore = await todayKpi()

  try {
    await btn.click()
    await expect(btn).toHaveAttribute('aria-pressed', 'true')
    await expect(btn).toHaveAttribute('aria-label', /생성 중/)
    await expect.poll(total, { timeout: 25_000, message: 'events are being added' }).toBeGreaterThan(idle)
    await expect.poll(todayKpi, { timeout: 25_000, message: 'dashboard refreshed without a reload' }).toBeGreaterThan(kpiBefore)
    await expect(page.getByText(ONLINE_CLAIM)).toHaveCount(0)
  } finally {
    if ((await btn.getAttribute('aria-pressed')) === 'true') await btn.click()
  }
  await expect(btn).toHaveAttribute('aria-pressed', 'false')
  await page.waitForTimeout(1000)                                                      // let a tick that was already running finish
  const stopped = await total()
  await page.waitForTimeout(3500)
  expect(await total(), 'no events after releasing the button').toBe(stopped)
})

test('ops: admin accounts can be added and disabled; the list never shows secrets', async ({ page }, info) => {
  await login(page)
  await goTo(page, '운영 설정')
  const box = page.getByTestId('admin-accounts')
  await expect(box).toContainText('(나)')
  const name = `e2e${info.project.name.slice(-4)}${Date.now().toString().slice(-6)}`
  await box.getByRole('button', { name: '관리자 추가' }).click()
  await page.getByLabel(/아이디/).fill(name)
  await page.getByLabel(/초기 비밀번호/).fill('e2e-initial-password-1')
  await page.getByRole('button', { name: '추가', exact: true }).click()
  await expect(box.getByRole('listitem').filter({ hasText: name })).toBeVisible()
  await box.getByRole('listitem').filter({ hasText: name }).getByRole('button', { name: '비활성화' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '비활성화' }).click()
  await expect(box.getByRole('listitem').filter({ hasText: name })).toContainText('비활성')
  expect(await page.content()).not.toContain('e2e-initial-password-1')
  await expectNoHorizontalScroll(page)
})

test('dashboard drill-down: every card, the trend and the top list open the screen that explains the number', async ({ page }) => {
  await login(page)
  const cardValue = async (id: string) => {
    const value = page.getByTestId(id).locator('p.text-3xl')
    await expect(value).toHaveText(/^[\d,]+$/)                       // wait for the summary to load ("–" while loading)
    return Number((await value.textContent())!.replace(/,/g, ''))
  }

  // 오늘 위반 건수 -> events of today (Seoul calendar day), same count
  const today = await cardValue('kpi-today')
  await page.getByTestId('kpi-today').click()
  await expect(page).toHaveURL(/\/events\?from=(\d{4}-\d{2}-\d{2})&to=\1$/)
  await expect(page.getByRole('heading', { name: '위반 이벤트', level: 1 })).toBeVisible()
  if (today > 0) await expect(page.getByTestId('pagination-info')).toContainText(`총 ${today.toLocaleString()}건`)
  else await expect(page.getByText('조건에 맞는 이벤트가 없습니다')).toBeVisible()

  // 배터리 주의 / 최근 24시간 수신 -> device list filtered with the same rule, filter chip shown and removable
  for (const [card, query, chip] of [['kpi-battery', 'low_battery=1', '배터리 주의 장비'], ['kpi-received', 'received_within_hours=24', '최근 24시간 내 수신']] as const) {
    await page.goto('/')
    const n = await cardValue(card)
    await page.getByTestId(card).click()
    await expect(page).toHaveURL(new RegExp(query))
    await expect(page.getByTestId('device-filters')).toContainText(chip)
    if (n > 0) await expect(page.getByTestId('pagination-info')).toContainText(`총 ${n.toLocaleString()}건`)
    else await expect(page.getByText(/없습니다/).first()).toBeVisible()
    await page.getByTestId('device-filters').getByRole('button').first().click()
    await expect(page.getByTestId('device-filters')).toHaveCount(0)
    await expect(page).not.toHaveURL(new RegExp(query))
  }

  // 등록 장비 수 -> active devices only
  await page.goto('/')
  await page.getByTestId('kpi-devices').click()
  await expect(page).toHaveURL(/hide_inactive=1/)

  // trend point -> that day's events ; keyboard works too
  await page.goto('/')
  const day = page.getByTestId('trend-day').last()
  await day.focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/events\?from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/)

  // top device -> its events in the 7-day period
  await page.goto('/')
  const top = page.getByRole('region', { name: '장비별 위반 상위 (7일)' }).getByRole('button').first()
  await expect(top).toBeVisible()
  await top.click()
  await expect(page).toHaveURL(/\/events\?device_id=[^&]+&from=.+&to=.+/)
  await expectNoHorizontalScroll(page)
})
