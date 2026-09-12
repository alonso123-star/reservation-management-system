import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1, // Each independent test resets ONLY the disposable rms-e2e database.
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 120000,
  expect: { timeout: 15000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  outputDir: 'test-results',
  use: {
    baseURL: 'http://localhost:3002',
    locale: 'es-PE',
    timezoneId: 'America/Lima',
    // Traces contain HTTP credentials. Keep them disabled; failure screenshots contain synthetic UI only.
    trace: 'off', video: 'off', screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
