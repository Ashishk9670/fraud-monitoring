# Load tests (k6)

`ingest-burst.js` ramps `POST /api/transactions` (+ batches of 20) to `PEAK_RPS` (default 200) and holds it for
`DURATION` (default 30s). 10% of the traffic comes from "hopping" accounts so the fraud rules fire under load.
Thresholds: <1% failed requests, p95 < 500 ms.

```bash
make app-up                                   # or: docker compose up -d --wait && mvn spring-boot:run
k6 run -e API_KEY=dev-local-key loadtest/ingest-burst.js
k6 run -e PEAK_RPS=500 -e DURATION=2m loadtest/ingest-burst.js
```

Run it from CI: Actions -> "Load test" -> Run workflow (inputs: peak RPS, duration).
