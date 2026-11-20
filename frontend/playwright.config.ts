import { defineConfig } from '@playwright/test'
import { readFileSync } from 'node:fs'

// Load ../.env (gitignored) so credentials never live in the repo or the command line.
try {
  for (const line of readFileSync(new URL('../.env', import.meta.url), 'utf8').split('\n')) {
    const m = line.match(/^([A-Z_]+)=(.*)$/)
    if (m && !(m[1] in process.env)) process.env[m[1]] = m[2]
  }
} catch {
  /* .env optional when variables are already exported */
}

const base = { channel: 'chrome' as const, baseURL: 'http://localhost:5173', locale: 'ko-KR', timezoneId: 'Asia/Seoul' }

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: { ...base, trace: 'retain-on-failure' },
  projects: [
    { name: 'desktop-1440', use: { ...base, viewport: { width: 1440, height: 900 } } },
    { name: 'tablet-768', use: { ...base, viewport: { width: 768, height: 1024 } } },
    { name: 'mobile-360', use: { ...base, viewport: { width: 360, height: 740 }, hasTouch: true } },
  ],
  webServer: { command: 'npm run dev -- --host 127.0.0.1', url: 'http://localhost:5173', reuseExistingServer: true, timeout: 60_000 },
})
