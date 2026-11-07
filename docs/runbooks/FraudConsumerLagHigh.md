# FraudConsumerLagHigh

**Alert:** `fraud_consumer_group_lag` (end offset minus committed offset, per consumer `group` and `topic`) above 1000 records for 5 minutes.

The built-in client metric `kafka_consumer_fetch_manager_records_lag_max` is deliberately not used: it measures how far the client has
fetched into its buffer, so a consumer stuck in slow processing still looks healthy. `ConsumerGroupLagMonitor` reads committed offsets
from the brokers (as `kafka-consumer-groups.sh --describe` does) every 15 s. Cross-check with that command if in doubt.

## Impact
Alerts, cases and analytics trail the real-time stream.

## Diagnose
1. Which group and topic? `analytics-sink` lag is the ClickHouse copy (best effort, detection is unaffected); `fraud-monitoring-streams` lag delays detection; `alert-sink` lag delays case creation. Transactions in `transactions` lag delay detection, `fraud-alerts` lag delays case creation.
2. Throughput spike? Compare with `fraud_transactions_published_total` rate.
3. Consumer errors: Loki `{service="app"} |= "Could not index"` or `|= "Exception"`.
4. Slow sink (Postgres / ClickHouse) - see [FraudAlertLatencyHigh](FraudAlertLatencyHigh.md).

## Mitigate
- Scale out replicas (up to the partition count) or raise partitions.
- Fix or temporarily bypass the slow sink; analytics indexing is best effort.
- If a poison message blocks progress, the Streams deserialization handler skips malformed records; for listeners check the `Skipping malformed alert` logs.
