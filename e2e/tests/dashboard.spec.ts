import { test, expect } from '@playwright/test';

test('dashboard loads and connects to the live alert stream', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveTitle(/Fraud Analyst Dashboard/);
  await expect(page.getByTestId('status')).toHaveText('live');
  await expect(page.getByTestId('alert-count')).toHaveText('0');
});

test('simulating a fraud burst shows a live alert and a new case', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('status')).toHaveText('live');

  await page.getByTestId('burst-btn').click();

  const row = page.getByTestId('alert-row').first();
  await expect(row).toBeVisible();
  await expect(row).toContainText('VELOCITY_MULTI_LOCATION');
  await expect(row).toContainText('acct-ui-');
  await expect(page.getByTestId('alert-count')).not.toHaveText('0');

  // the case created from the alert is listed
  await expect(page.getByTestId('case-row').filter({ hasText: 'acct-ui-' }).first()).toBeVisible();
});

test('analyst can move a case to IN_REVIEW', async ({ page, request }) => {
  const account = `acct-review-${Date.now()}`;
  await page.goto('/');
  await expect(page.getByTestId('status')).toHaveText('live');
  await request.post(`/api/simulator/fraud-burst?accountId=${account}&count=12`);

  const caseRow = page.getByTestId('case-row').filter({ hasText: account }).first();
  await expect(caseRow).toBeVisible();
  await caseRow.getByRole('button', { name: 'Review' }).click();
  await expect(page.getByTestId('case-row').filter({ hasText: account }).first()).toContainText('IN_REVIEW');
});

test('GraphQL API serves cases over HTTP', async ({ request }) => {
  const res = await request.post('/graphql', { data: { query: '{ cases(limit: 5) { id status } }' } });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  expect(body.errors).toBeUndefined();
  expect(Array.isArray(body.data.cases)).toBeTruthy();
});

test('REST ingest validates input', async ({ request }) => {
  const bad = await request.post('/api/transactions', { data: { accountId: '' } });
  expect(bad.status()).toBe(400);
  const ok = await request.post('/api/transactions', {
    data: { accountId: 'e2e-ok', merchantId: 'm', amount: 10, currency: 'USD', location: 'US' },
  });
  expect(ok.status()).toBe(202);
});
