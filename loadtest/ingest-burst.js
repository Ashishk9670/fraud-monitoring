// k6 ingest burst: ramps up POST /api/transactions and /api/transactions/batch, with a few multi-location
// "fraud" accounts mixed in so the Kafka Streams rules fire under load.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=dev-local-key loadtest/ingest-burst.js
//   k6 run -e PEAK_RPS=300 -e DURATION=2m loadtest/ingest-burst.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'dev-local-key';
const PEAK_RPS = parseInt(__ENV.PEAK_RPS || '200', 10);
const DURATION = __ENV.DURATION || '30s';

const rejected = new Counter('ingest_rejected');
const LOCATIONS = ['US', 'GB', 'DE', 'IN', 'BR', 'JP'];

export const options = {
  scenarios: {
    burst: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 400,
      stages: [
        { target: PEAK_RPS, duration: '10s' },   // ramp up
        { target: PEAK_RPS, duration: DURATION }, // sustained burst
        { target: 0, duration: '5s' },           // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

const headers = { 'Content-Type': 'application/json', 'X-API-Key': API_KEY };

function txn(account, location) {
  return {
    accountId: account,
    merchantId: `m-${Math.floor(Math.random() * 50)}`,
    amount: Math.round(Math.random() * 20000) / 100 + 1,
    currency: 'USD',
    location,
  };
}

export default function () {
  const r = Math.random();
  let res;
  if (r < 0.1) {
    // a hopping account: many txns from several locations -> VELOCITY_MULTI_LOCATION
    const acct = `lt-fraud-${__VU}-${Math.floor(__ITER / 20)}`;
    res = http.post(`${BASE_URL}/api/transactions`, JSON.stringify(txn(acct, LOCATIONS[__ITER % LOCATIONS.length])), { headers });
  } else if (r < 0.3) {
    const batch = Array.from({ length: 20 }, () => txn(`lt-${Math.floor(Math.random() * 5000)}`, 'US'));
    res = http.post(`${BASE_URL}/api/transactions/batch`, JSON.stringify(batch), { headers });
  } else {
    res = http.post(`${BASE_URL}/api/transactions`, JSON.stringify(txn(`lt-${Math.floor(Math.random() * 5000)}`, 'US')), { headers });
  }
  const ok = check(res, { 'accepted (202)': (x) => x.status === 202 });
  if (!ok) rejected.add(1);
}
