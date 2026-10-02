import { defineConfig, devices } from '@playwright/test';

const API_KEY = process.env.API_KEY ?? 'dev-local-key';

// The stack (docker compose + app) must already be running; BASE_URL points at the app.
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 60_000 },
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:8080',
    extraHTTPHeaders: { 'X-API-Key': API_KEY },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
