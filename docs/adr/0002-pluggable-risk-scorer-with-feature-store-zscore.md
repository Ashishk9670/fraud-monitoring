# ADR 0002: Pluggable `RiskScorer` with a feature-store z-score default

Status: accepted

## Context
Rule hits are binary. Analysts want a graded signal to prioritise cases, and teams may later want to swap in a model
without touching the topology.

## Decision
- `RiskScorer` (`OptionalDouble score(Transaction, Map<String,String> features)`) is the extension point; a user-defined
  bean replaces the default (`@ConditionalOnMissingBean`).
- The default `ZScoreRiskScorer` computes a one-sided z-score of the amount against the account's history held in the
  Redis feature store. The store now keeps `total_amount_sq` next to `txn_count` and `total_amount`, which is enough for
  mean and variance without storing transactions. The current transaction is subtracted, because the feature update
  runs before rule evaluation. Risk = `z / 6` clamped to [0, 1]; fewer than 5 prior transactions yields no score.
- The score is an optional `riskScore` on `FraudAlert`, persisted in ClickHouse (`risk_score Nullable(Float64)`) and
  exposed in GraphQL. It does not change severity or the rule outcome.
- Scoring is best effort: scorer or feature-store failures are logged and produce a null score.

## Consequences
- Rules stay deterministic and the score is advisory, so existing behaviour and tests are unchanged.
- Sums of squares can lose precision for very large histories; acceptable for a heuristic, and a custom scorer can
  use better statistics.
- The live pipeline scores against current features; the replay pipeline does not score (it has no point-in-time
  features).
