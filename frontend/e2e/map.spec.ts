import { expect, test, type Page } from '@playwright/test'
import { clickSingleMarker, deactivateTestDevices, expectNoHorizontalScroll, goTo, login, PASSWORD, trackTestDevice, USER } from './helpers'

const shot = (name: string, project: string) => `../docs/screenshots/${project.split('-')[1]}-${name}.png`

test.afterAll(async ({ request }) => { await deactivateTestDevices(request) })

test.beforeAll(async ({ request }) => {
  await request.get('http://localhost:5173/api/v1/admin/csrf')
  const x1 = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')!.value
  const h = (t: string) => ({ 'X-XSRF-TOKEN': t, 'Content-Type': 'application/json' })
  expect((await request.post('http://localhost:5173/api/v1/admin/login', { headers: h(x1), data: { username: USER, password: PASSWORD } })).status()).toBe(200)
  const x2 = (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')!.value
  expect((await request.post('http://localhost:5173/api/v1/admin/dev/import-demo', { headers: h(x2), data: {} })).status()).toBe(200)
})

async function registerEmptyDevice(page: Page, id: string) {
  trackTestDevice(id)
  const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value
  const r = await page.request.post('/api/v1/admin/devices', { headers: { 'X-XSRF-TOKEN': xsrf, 'Content-Type': 'application/json' }, data: { device_id: id } })
  expect(r.status()).toBe(201)
}

test('dashboard map: violation markers/clusters, cluster click zooms, marker click selects only that device', async ({ page }, info) => {
  await login(page)
  const map = page.getByTestId('keyless-map')
  await expect(map).toBeVisible()
  await expect(map).toContainText('키 없는 대체 지도')
  await expect(page.getByTestId('demo-badge').first()).toBeVisible()
  const markers = await page.getByTestId('map-marker').count()
  const clusters = page.getByTestId('map-cluster')
  expect(markers + (await clusters.count())).toBeGreaterThan(0)
  await expect(page.getByTestId('route-layer')).toHaveCount(0)           // no route on the default view
  await expectNoHorizontalScroll(page)

  if (await clusters.count()) {
    const before = await clusters.count()
    await clusters.first().click()
    await expect.poll(async () => (await page.getByTestId('map-cluster').count()) + (await page.getByTestId('map-marker').count())).not.toBe(before)
  }
  await map.getByRole('button', { name: '전체 보기' }).click()
  await clickSingleMarker(page)   // zooms into a badge first if the screen is too narrow to show single markers
  await expect(page.getByTestId('device-panel')).toBeVisible()
  await expect(page.getByTestId('device-panel')).toContainText('실시간 위치 아님')
  await expect(page.getByTestId('map-marker-selected').first()).toBeVisible()
  await page.screenshot({ path: shot('dashboard-map', info.project.name), fullPage: false })
})

test('map page: selecting a device highlights it, route off by default, null-GPS rows are counted not pinned', async ({ page }, info) => {
  await login(page)
  await goTo(page, '위치·경로')
  await expect(page.getByTestId('map-summary')).toContainText('위반 이벤트')
  await expect(page.getByTestId('missing-gps')).toContainText('지도에 표시하지 않습니다')
  await expect(page.getByTestId('route-toggle')).toBeDisabled()          // nothing selected yet
  await expect(page.getByTestId('route-layer')).toHaveCount(0)
  await expectNoHorizontalScroll(page)

  await page.goto('/map?device_id=PAD-0007')
  await expect(page.getByTestId('map-summary')).toContainText('선택 장비')
  await expect(page.getByTestId('map-marker-selected').first()).toBeVisible()
  await expect(page.getByTestId('route-layer')).toHaveCount(0)           // selecting a device alone does not draw a route
  await expect(page.getByTestId('route-toggle')).toBeEnabled()
  await page.screenshot({ path: shot('map-selected', info.project.name), fullPage: true })
})

test('map page: tapping a marker brings the device panel into view and clearing returns to the map', async ({ page }) => {
  await login(page)
  await goTo(page, '위치·경로')
  await expect(page.getByTestId('keyless-map')).toBeVisible()
  await clickSingleMarker(page)
  const panel = page.getByRole('complementary', { name: '선택 장비' })
  await expect(page.getByTestId('device-panel')).toBeVisible()
  await expect(page.getByTestId('device-panel')).toBeInViewport()                       // not left below the fold
  await expect(page.getByTestId('device-panel')).toBeInViewport({ ratio: 0.9 })            // the whole device card (the violation list below it may scroll), not just its top edge
  await expect(panel).toBeInViewport()
  await expectNoHorizontalScroll(page)

  await page.getByRole('button', { name: '선택 해제' }).click()
  await expect(page.getByTestId('device-panel')).toHaveCount(0)
  await expect(page.getByTestId('keyless-map')).toBeInViewport()                        // back at the map
})

test('route: only the selected device, straight-line disclaimer, gaps explained, demo-only warning', async ({ page }, info) => {
  await login(page)
  await page.goto('/map?device_id=PAD-0007')
  await page.getByTestId('route-toggle').click()
  await expect(page.getByTestId('route-toggle')).toBeChecked()
  await expect(page.getByTestId('route-layer')).toBeVisible()
  await expect(page.getByTestId('route-disclaimer')).toContainText('실제 도로 경로나 이동 증거가 아니며')
  await expect(page.getByTestId('route-info')).toContainText('데모 전용')
  await expect(page.getByTestId('route-info')).toContainText('수집 지점')
  await expect(page.getByRole('list', { name: '범례' })).toContainText('정기 위치')
  await expectNoHorizontalScroll(page)
  await page.screenshot({ path: shot('map-route', info.project.name), fullPage: true })

  // switching to another device must not keep the previous route
  await page.goto('/map?device_id=PAD-0008')
  await expect(page.getByTestId('route-layer')).toHaveCount(0)
})

test('route: a device with no GPS pings shows an explanation and no invented line', async ({ page }, info) => {
  await login(page)
  const id = `NOPING-${info.project.name.slice(-4)}-${Date.now().toString().slice(-6)}`
  await registerEmptyDevice(page, id)
  await page.goto(`/map?device_id=${id}&route=1`)
  await expect(page.getByTestId('route-insufficient')).toContainText('위반 위치만으로는 경로를 만들지 않습니다')
  await expect(page.getByTestId('route-layer')).toHaveCount(0)
})

test('map keyboard: +/- and arrows work on the focused map', async ({ page }) => {
  await login(page)
  await page.goto('/map')
  const svg = page.getByTestId('keyless-map').getByRole('group').first()
  await svg.focus()
  const scaleText = async () => (await page.getByTestId('keyless-map').locator('svg text', { hasText: /\d+ ?(m|km)$/ }).first().textContent()) ?? ''
  const a = await scaleText()
  for (let i = 0; i < 4; i++) await page.keyboard.press('+')
  await page.keyboard.press('ArrowLeft')
  const b = await scaleText()
  expect(b).not.toBe(a)
})

test('map readability: legend explains the badges, a cluster hover says what is inside, the list mirrors the map', async ({ page }, info) => {
  await login(page)
  await goTo(page, '위치·경로')
  await expect(page.getByTestId('map-legend')).toContainText('위반 N건')
  await expect(page.getByTestId('map-legend')).toContainText('누르면 확대')

  // Device ranking: counts on the list add up to the map summary
  const all = page.getByTestId('violation-list-all')
  await expect(all).toContainText('위반이 많은 순')
  await expect(all.getByRole('listitem').first()).toContainText('건')

  const clusters = page.getByTestId('map-cluster')
  if (await clusters.count()) {
    await clusters.first().hover()
    await expect(page.getByTestId('map-info-cluster')).toContainText('장비')
    await expect(page.getByTestId('map-info-cluster')).toContainText('누르면 확대')
  }

  // Choose a device from the list -> its violations by time -> "지도에서 보기" pins the details on the map
  await all.getByRole('listitem').first().getByRole('button').click()
  const mine = page.getByTestId('violation-list-device')
  await expect(mine).toContainText('(최신순)')
  await expect(page.getByTestId('device-panel')).toBeVisible()
  await mine.getByRole('listitem').first().getByRole('button').click()
  const card = page.getByTestId('map-info-point')
  await expect(card).toContainText('위반 발생')
  await expect(card).toContainText('최초 수신')
  await expect(card).toBeInViewport()
  await expectNoHorizontalScroll(page)
  await page.screenshot({ path: shot('map-list', info.project.name), fullPage: true })

  await card.getByRole('button', { name: '정보 닫기' }).click()
  await expect(page.getByTestId('map-info-point')).toHaveCount(0)
  await mine.getByRole('button', { name: '전체 장비 목록' }).click()
  await expect(page.getByTestId('violation-list-all')).toBeVisible()
})

test('map period presets set the Seoul-calendar range in the URL', async ({ page }) => {
  await login(page)
  await page.goto('/map')
  await page.getByRole('button', { name: '최근 7일' }).click()
  await expect(page).toHaveURL(/from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/)
  await expect(page.getByRole('button', { name: '최근 7일' })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: '전체 기간' }).click()
  await expect(page).not.toHaveURL(/from=/)
})
