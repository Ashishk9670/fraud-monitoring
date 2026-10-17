# ADR 0003: Replay through an isolated pipeline and replay topics

Status: accepted

## Context
We want to re-run historical transactions through the current rules (for example to preview a rule change). Feeding
them into `transactions` would double count window state, bump Redis velocity counters and features, and open duplicate
cases, alerts and push notifications.

## Decision
- `POST /api/replay` reads a bounded time range (max 7 days, limit 100 000) from the `AnalyticsStore` (ClickHouse by
  default) and publishes it to `transactions-replay`, keyed `replayId|accountId`.
- The same topology contains a second pipeline built from the same rule code: its own window store, no Redis side
  effects, output on `fraud-alerts-replay`. Nothing on the live path (case creation, analytics, GraphQL push) consumes
  that topic.
- The replay id in the key isolates window state between runs and lets `ReplayAlertCollector` attribute alerts to a
  run. Results are available at `GET /api/replay/{id}` and `/alerts` from a bounded in-memory collector (20 runs, 10 000
  alerts each); the Kafka topic remains the durable record.
- The replay window store has a 60-day retention so historical event times are not dropped as expired.

## Consequences
- Replays are safe to run against a live system and always use the rules currently in force.
- Replay results are not durable in the application (restart clears the collector) and are not scored.
- Stores for finished replays keep state until retention expires; the replay changelog is cleaned up by Kafka retention.
