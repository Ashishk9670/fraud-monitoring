# FraudPublishFailures

**Alert:** `fraud_transactions_publish_failed_total` is increasing: Kafka did not acknowledge published transactions.

## Impact
Ingested transactions may be lost (the API answers 202 before the broker acknowledges).

## Diagnose
1. Is the broker reachable from the app? Network policy, DNS, broker health.
2. Broker disk/ISR issues, `NOT_ENOUGH_REPLICAS`, authentication errors: Loki `{service="app"} |= "kafka"`.
3. Producer metrics: `kafka_producer_*` for record error rate and buffer exhaustion.

## Mitigate
- Restore the broker; the producer retries until `delivery.timeout.ms` (default 2 minutes), so a short outage loses nothing.
- After a longer outage clients should replay the transactions that failed (the failure counter bounds how many); the replay tool can re-feed stored ranges from analytics.
