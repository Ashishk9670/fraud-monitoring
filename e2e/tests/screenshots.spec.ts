import { test, expect } from '@playwright/test';

// Captures README screenshots into docs/img. Opt-in: SCREENSHOTS=1 (see `make screenshots`).
const API_KEY = process.env.API_KEY ?? 'dev-local-key';
test.skip(!process.env.SCREENSHOTS, 'set SCREENSHOTS=1 to capture docs/img');
test.use({ viewport: { width: 1400, height: 900 } });

test('capture dashboard screenshots', async ({ page, request }) => {
  await page.addInitScript((key) => localStorage.setItem('fraudApiKey', key), API_KEY);
  await page.goto('/');
  await expect(page.getByTestId('status')).toHaveText('live');
  await page.screenshot({ path: '../docs/img/dashboard-empty.png' });

  for (const acct of ['acct-demo-1', 'acct-demo-2', 'acct-demo-3']) {
    await request.post(`/api/simulator/fraud-burst?accountId=${acct}&count=12`);
  }
  await expect(page.getByTestId('case-row').filter({ hasText: 'acct-demo-3' }).first()).toBeVisible();
  await expect(page.getByTestId('alert-row').first()).toBeVisible();
  await page.screenshot({ path: '../docs/img/dashboard-alerts.png', fullPage: true });
});
