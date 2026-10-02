# Real-Time Fraud / Transaction Monitoring

Spring Boot 3.3 / Java 21 service that ingests payment transactions, detects fraud in real time with
Kafka Streams, and exposes cases, alerts and a live alert stream to analysts via GraphQL and a small web dashboard.

## Architecture

```
 REST  POST /api/transactions ─┐
 Simulator (scheduled / REST) ─┴─► Kafka topic `transactions` (key = accountId)
                                          │
                       ┌──────────────────┴───────────────────┐
                       ▼                                      ▼
              Kafka Streams topology                 TransactionAnalyticsConsumer ─► ClickHouse (transactions)
   ┌──────────────────────────────────────────┐
   │ 1m tumbling window per account:          │      Redis (via interfaces, used inside the topology)
   │   > 5 txns from >= 2 locations  ─┐       │        • velocity counters  velocity:<1m|1h>:<acct>:<bucket>  (TTL)
   │ single txn >= 10,000 (HIGH_VALUE)├──► `fraud-alerts` topic   • blocklist  blocklist:<type>:<value>   (optional TTL)
   │ account/merchant/location on     │       │                 • feature store features:<acct> hash       (TTL 24h)
   │   blocklist (BLOCKLIST_HIT)     ─┘       ▼
   └──────────────────────────────────────────┘   AlertConsumer
                                                    ├─► PostgreSQL `fraud_cases` (JPA + Flyway, idempotent per alert)
                                                    ├─► AnalyticsStore (ClickHouse `alerts`)
                                                    └─► AlertBroadcaster ─► GraphQL subscription `alertStream`
 Analyst dashboard (/)  ◄── GraphQL (queries, mutation, subscription over WebSocket)
```

* **Kafka Streams** (`streams/FraudTopology`): event-time windows (`TransactionTimestampExtractor`), a stateful
  `TxnStats` aggregate that flags the *first* threshold crossing per window so each window alerts once. Record caching is
  disabled (`statestore.cache.max.bytes=0`) so no update is coalesced away.
* **Redis**: `BlocklistService`, `VelocityCounter`, `FeatureStore` interfaces with Redis implementations; the topology
  only sees the interfaces (easy to unit test). Redis failures are logged and never kill the stream thread.
* **Analytics**: `AnalyticsStore` interface; ClickHouse implementation talks to the HTTP interface (no JDBC driver).
  `analytics.store=memory` selects an in-memory implementation.
* **PostgreSQL**: `fraud_cases` table managed by Flyway (`db/migration`), JPA entity `FraudCase`.
* **GraphQL** (`graphql/schema.graphqls`): queries `cases`, `case`, `alerts`, `alertCountsByRule`, `topRiskAccounts`;
  mutation `updateCase`; subscription `alertStream(minSeverity)`. GraphiQL at `/graphiql`.

## Run it

Requirements: JDK 21, Maven, Docker.

```bash
docker compose up -d --wait          # Kafka, Redis, Postgres, ClickHouse
mvn spring-boot:run                  # or: make run
# with continuous synthetic traffic (+ occasional fraud bursts):
mvn spring-boot:run -Dspring-boot.run.arguments=--simulator.enabled=true
```

Open http://localhost:8080 (dashboard) or http://localhost:8080/graphiql.

```bash
# ingest a transaction
curl -XPOST localhost:8080/api/transactions -H 'content-type: application/json' \
  -d '{"accountId":"acct-1","merchantId":"m-1","amount":42.5,"currency":"USD","location":"US"}'

# trigger a multi-location fraud burst (12 txns hopping between countries)
curl -XPOST 'localhost:8080/api/simulator/fraud-burst?accountId=acct-demo&count=12'

# blocklist an account (optionally ttlSeconds), inspect Redis features
curl -XPOST localhost:8080/api/blocklist -H 'content-type: application/json' -d '{"type":"ACCOUNT","value":"acct-9","reason":"mule"}'
curl localhost:8080/api/accounts/acct-demo/features
```

GraphQL examples:

```graphql
subscription { alertStream(minSeverity: HIGH) { accountId rule severity reason } }
{ cases(status: OPEN, limit: 20) { id accountId rule severity status } }
mutation { updateCase(id: "<uuid>", status: CONFIRMED_FRAUD, assignee: "alice") { status } }
```

Rule thresholds live under `fraud.rules.*` in `application.yml`.

## Tests

| Layer | What | Needs Docker | Command |
|---|---|---|---|
| Unit | `TopologyTestDriver` topology tests, `TxnStats`, JSON serde, generator, Redis services (Mockito), alert consumer | no | `mvn test` |
| API | Full Spring context: REST (RestAssured), GraphQL queries/mutation/subscription, SSE, Kafka Streams end to end. Uses embedded Kafka, H2 (PostgreSQL mode), in-memory analytics and fake Redis (`application-test.yml`, `TestInfra`) | no | `mvn test` |
| Integration | `*IT` classes with Testcontainers: real Kafka, Redis, Postgres, ClickHouse (`FullStackIT`). Skipped automatically when Docker is unavailable | yes | `mvn verify` |
| E2E UI | Playwright (`e2e/`) drives the dashboard against a running stack | yes (or local stand-in) | see below |

Coverage: Jacoco runs with the tests; report at `target/site/jacoco/index.html` (`mvn verify`).

### Playwright e2e

```bash
docker compose up -d --wait && mvn -DskipTests package && java -jar target/fraud-monitoring-*-SNAPSHOT.jar &
cd e2e && npm ci && npx playwright install chromium && npx playwright test   # BASE_URL overrides http://localhost:8080
```

Without Docker you can run the same suite against a stand-in app (embedded Kafka, H2, fake Redis):
`make e2e-local-app` in one terminal, `make e2e` in another.

### CI

`.github/workflows/ci.yml`: build + unit/API tests with Jacoco, Testcontainers integration tests, and Playwright e2e
(`docker compose up`, app jar, browsers installed, HTML report uploaded as an artifact).

## Layout

```
src/main/java/com/example/fraud
  streams/    FraudTopology, TxnStats, timestamp extractor
  redis/      blocklist, velocity counters, feature store (interfaces + Redis impls)
  analytics/  AnalyticsStore, ClickHouse + in-memory impls
  cases/      JPA entity, repository, service
  messaging/  Kafka producer/consumers, live alert broadcaster
  graphql/    GraphQL controller          web/  REST controllers
  simulator/  synthetic data generator + scheduler
```
