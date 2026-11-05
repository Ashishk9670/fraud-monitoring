# FraudAppDown

**Alert:** Prometheus cannot scrape `up{job="fraud-monitoring"}` for 2 minutes.

## Impact
The service may be down, or the metrics endpoint is unreachable/unauthorised.

## Diagnose
1. `kubectl get pods` / `docker compose ps`: is the app running, restarting, or OOM-killed (`ExitOnOutOfMemoryError` is set)?
2. Liveness/readiness: `GET /actuator/health/liveness`.
3. Scrape config: the endpoint needs the API key as a bearer token (see `observability/prometheus/prometheus.yml`).
4. Logs: Loki `{service="app"}`.

## Mitigate
- Restart / roll back the deployment; check resource limits.
- If only the scrape fails, fix the credentials or network path.
