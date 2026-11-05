package com.example.fraud.scoring;

import com.example.fraud.model.Transaction;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Pluggable risk scoring hook. Implementations return an anomaly/risk score in [0, 1] for a transaction that raised an
 * alert, or empty when there is not enough information. Declare your own {@code RiskScorer} bean to replace the default
 * heuristic ({@link ZScoreRiskScorer}).
 */
@FunctionalInterface
public interface RiskScorer {
    /**
     * @param txn      the transaction that triggered the alert
     * @param features the account's feature-store snapshot, as of and including {@code txn}
     */
    OptionalDouble score(Transaction txn, Map<String, String> features);

    static RiskScorer none() {
        return (t, f) -> OptionalDouble.empty();
    }
}
