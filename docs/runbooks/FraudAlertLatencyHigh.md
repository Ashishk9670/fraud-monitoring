# FraudAlertLatencyHigh

**Alert:** p95 of `fraud_alert_latency_seconds` above 5s for 5 minutes (SLO: 95% of alerts become a case within 5s of the triggering transaction).

## Impact
Analysts see fraud alerts late; time-to-block grows.

## Diagnose
1. Grafana "Fraud Monitoring" dashboard: is consumer lag also high? If so see [FraudConsumerLagHigh](FraudConsumerLagHigh.md).
2. Is the Streams topology RUNNING? `kafka_streams_*` metrics, or Loki: `{service="app"} |= "StreamThread"`.
3. Slow side effects: Postgres (case insert) and analytics (ClickHouse) latency. Check Loki for `Analytics indexing failed` and slow queries in Postgres.
4. Redis down? The topology fails fast but logs `Redis side-effect failed`; this adds little latency but check anyway.
5. Open a slow trace in Tempo (Explore -> Tempo, filter on `service.name=fraud-monitoring`) and look for the long span.

## Mitigate
- Restart a stuck Streams thread by restarting the pod/container (state is rebuilt from the changelog topic).
- If ClickHouse is the bottleneck, alert indexing is already best effort; scale or fix ClickHouse.
- Scale out consumers (more partitions and replicas) if sustained load exceeds capacity.
