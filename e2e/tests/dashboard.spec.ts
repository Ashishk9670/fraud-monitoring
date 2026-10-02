import { test, expect } from '@playwright/test';

const API_KEY = process.env.API_KEY ?? 'dev-local-key';

// The dashboard reads its API key from localStorage (set via the "Set key" box in the header).
test.beforeEach(async ({ page }) => {
  await page.addInitScript((key) => localStorage.setItem('fraudApiKey', key), API_KEY);
});

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

test('API rejects requests without a key', async ({ playwright, baseURL }) => {
  const anon = await playwright.request.newContext({ baseURL, extraHTTPHeaders: {} });
  expect((await anon.post('/graphql', { data: { query: '{ cases { id } }' } })).status()).toBe(401);
  expect((await anon.get('/actuator/health/liveness')).status()).toBe(200);
  await anon.dispose();
});

// Proves the real stack is wired: Redis feature store, Postgres cases, ClickHouse analytics, Prometheus metrics.
test('fraud burst lands in Redis, Postgres, ClickHouse and metrics', async ({ request }) => {
  const account = `e2e-stack-${Date.now()}`;
  await request.post(`/api/simulator/fraud-burst?accountId=${account}&count=12`);

  await expect.poll(async () => {
    const r = await request.post('/graphql', { data: { query: '{ cases(limit: 100) { accountId } }' } });
    return (await r.json()).data.cases.map((c: { accountId: string }) => c.accountId);
  }, { timeout: 60_000 }).toContain(account);                                         // Postgres

  await expect.poll(async () => {
    const r = await request.post('/graphql', { data: { query: '{ alerts(limit: 100) { accountId } }' } });
    return (await r.json()).data.alerts.map((a: { accountId: string }) => a.accountId);
  }, { timeout: 60_000 }).toContain(account);                                         // ClickHouse (analytics.store=clickhouse)

  await expect.poll(async () => {
    const r = await request.get(`/api/accounts/${account}/features`);
    return (await r.json()).features?.txn_count;
  }, { timeout: 60_000 }).toBe('12');                                                 // Redis feature store

  const metrics = await (await request.get('/actuator/prometheus')).text();
  expect(metrics).toContain('fraud_alerts_total{');
});
