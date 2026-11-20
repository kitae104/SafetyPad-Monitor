import { expect, type APIRequestContext, type Page } from '@playwright/test'

export const USER = process.env.ADMIN_BOOTSTRAP_USERNAME ?? 'admin'
export const PASSWORD = process.env.ADMIN_BOOTSTRAP_PASSWORD ?? ''

/**
 * These scenarios assert on the key-less SVG map (testids, clusters, keyboard). A dev server configured with a Kakao key
 * would render the real SDK instead, so the config response is switched to the key-less provider for the page.
 * Set E2E_REAL_MAP=1 to exercise whatever provider the server is configured with.
 */
async function forceKeylessMap(page: Page) {
  if (process.env.E2E_REAL_MAP) return
  await page.route('**/api/v1/admin/config', async (route) => {
    const res = await route.fetch()
    if (!res.ok()) return route.fulfill({ response: res })
    await route.fulfill({ response: res, json: { ...(await res.json()), map_provider: 'mock', map_js_key: null } })
  })
}

export async function login(page: Page) {
  await forceKeylessMap(page)
  await page.goto('/')
  await page.getByLabel('아이디').fill(USER)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.getByRole('heading', { name: '대시보드', level: 1 })).toBeVisible()
}

/** Desktop uses the fixed sidebar; narrow screens open the drawer first. */
export async function goTo(page: Page, label: string) {
  const menu = page.getByRole('button', { name: '메뉴 열기' })
  const link = page.getByRole('navigation', { name: '주 메뉴' }).getByRole('link', { name: label })
  await expect(menu.or(link)).toBeVisible()   // after a reload the shell may not be laid out yet; deciding too early skips the drawer click
  if (await menu.isVisible()) await menu.click()
  await page.getByRole('navigation', { name: '주 메뉴' }).getByRole('link', { name: label }).click()
}

export async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow, 'page must not scroll horizontally').toBeLessThanOrEqual(1)
}

// Devices registered by E2E scenarios. They are fixtures, not real devices: deactivate them when the file's tests end
// so they cannot be mistaken for production hardware (raw events are immutable, so devices are never deleted).
const testDevices: string[] = []
export function trackTestDevice(id: string) { testDevices.push(id) }

export async function deactivateTestDevices(request: APIRequestContext) {
  const ids = testDevices.splice(0)
  if (ids.length === 0) return
  const base = 'http://localhost:5173/api/v1/admin'
  const token = async () => (await request.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')!.value
  const headers = (t: string) => ({ 'X-XSRF-TOKEN': t, 'Content-Type': 'application/json' })
  await request.get(`${base}/csrf`)
  const login = await request.post(`${base}/login`, { headers: headers(await token()), data: { username: USER, password: PASSWORD } })
  expect(login.status(), 'E2E cleanup login').toBe(200)
  for (const id of ids) {
    const r = await request.post(`${base}/devices/${encodeURIComponent(id)}/active`, { headers: headers(await token()), data: { active: false } })
    expect(r.status(), `deactivate ${id}`).toBe(200)
  }
}

/**
 * Clicks a single (non-clustered) marker that is not under the zoom buttons or caption, like a user would.
 * When everything is merged into "위반 N건" badges (narrow screens), a user zooms in by pressing a badge - so does this.
 */
export async function clickSingleMarker(page: Page) {
  const map = page.getByTestId('keyless-map')
  await map.getByRole('button', { name: '전체 보기' }).click()
  const box = (await map.boundingBox())!
  const clear = (b: { x: number; y: number; width: number; height: number } | null) =>
    !!b && b.x > box.x + 20 && b.x + b.width < box.x + box.width - 80 && b.y > box.y + 40 && b.y + b.height < box.y + box.height - 60
  for (let attempt = 0; attempt < 5; attempt++) {
    const markers = page.getByTestId('map-marker')
    for (let i = 0; i < (await markers.count()); i++) {
      if (clear(await markers.nth(i).boundingBox())) {
        await markers.nth(i).click()
        return
      }
    }
    const clusters = page.getByTestId('map-cluster')
    let zoomed = false
    for (let i = 0; i < (await clusters.count()) && !zoomed; i++) {
      if (clear(await clusters.nth(i).boundingBox())) {
        await clusters.nth(i).click()
        zoomed = true
      }
    }
    if (!zoomed) break
  }
  throw new Error('no clickable single marker, even after zooming into clusters')
}
