# ADR 0001: Runtime rules stored in Postgres, read per record from a snapshot

Status: accepted

## Context
Analysts need to tune rule parameters (window size, count threshold, minimum distinct locations, high-value amount)
without a redeploy. Kafka Streams fixes `TimeWindows` when the topology is built, so a plain property change would need
a restart or a topology rebuild.

## Decision
- Rules live in a single-row Postgres table (`fraud_rule_config`), edited through authenticated REST and GraphQL and
  seeded from `fraud.rules.*` on first start.
- `RuleService` publishes an immutable volatile snapshot behind the `RulesProvider` interface. It refreshes immediately
  on update and polls the table (default 2 s) so every instance converges.
- The windowed rule is a Processor API step (`WindowedVelocityProcessor`) over a window store keyed by
  (account, epoch-aligned window start). The window size is resolved per record, so it can change at runtime.

## Consequences
- No restart or rebuild; a change is visible within one poll interval on other instances and immediately on the
  instance that served the update.
- A window-size change applies to windows opened afterwards; open windows keep their original boundaries.
- Window state is bounded by store retention (24 h), so the maximum configurable window (1 h) stays below it.
- We give up the DSL's grace-period handling; late records beyond retention are dropped by the store.
