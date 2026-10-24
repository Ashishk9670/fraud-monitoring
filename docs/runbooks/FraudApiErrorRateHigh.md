# FraudApiErrorRateHigh

**Alert:** more than 5% of HTTP requests return 5xx for 5 minutes.

## Impact
Transaction ingestion and/or the case/rules API is failing.

## Diagnose
1. Dashboard panel "HTTP requests by status": which `uri` is failing?
2. Loki: `{service="app"} |= "ERROR"` and correlate with the trace id in the log line (click "View trace in Tempo").
3. Dependencies: Postgres (cases, rules), Kafka (ingest), Redis (blocklist API). `/actuator/health` shows which is DOWN.
4. Recent deploy? Check the Argo CD / release history and roll back if the errors started with it.

## Mitigate
- Dependency outage: restore the dependency; the app reconnects on its own (Redis and Kafka clients retry, see the chaos tests).
- Bad release: roll back to the previous image.
- Overload: check the rate limiter / request size limits and scale out.
