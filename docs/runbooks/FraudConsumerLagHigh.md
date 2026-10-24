# FraudConsumerLagHigh

**Alert:** `kafka_consumer_fetch_manager_records_lag_max` above 1000 records for 5 minutes.

## Impact
Alerts, cases and analytics trail the real-time stream.

## Diagnose
1. Which topic (`topic` label)? `transactions` lag delays detection; `fraud-alerts` lag delays case creation.
2. Throughput spike? Compare with `fraud_transactions_published_total` rate.
3. Consumer errors: Loki `{service="app"} |= "Could not index"` or `|= "Exception"`.
4. Slow sink (Postgres / ClickHouse) - see [FraudAlertLatencyHigh](FraudAlertLatencyHigh.md).

## Mitigate
- Scale out replicas (up to the partition count) or raise partitions.
- Fix or temporarily bypass the slow sink; analytics indexing is best effort.
- If a poison message blocks progress, the Streams deserialization handler skips malformed records; for listeners check the `Skipping malformed alert` logs.
