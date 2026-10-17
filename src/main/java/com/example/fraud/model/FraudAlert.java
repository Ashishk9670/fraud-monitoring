package com.example.fraud.model;

import java.time.Instant;
import java.util.List;

public record FraudAlert(
        String id,
        String accountId,
        String transactionId,
        String rule,
        Severity severity,
        double score,
        String reason,
        int transactionCount,
        List<String> locations,
        Instant windowStart,
        Instant windowEnd,
        Instant createdAt,
        /** Optional anomaly score in [0,1] from the pluggable {@code RiskScorer}; null when unavailable. */
        Double riskScore) {

    public FraudAlert(String id, String accountId, String transactionId, String rule, Severity severity, double score,
                      String reason, int transactionCount, List<String> locations, Instant windowStart,
                      Instant windowEnd, Instant createdAt) {
        this(id, accountId, transactionId, rule, severity, score, reason, transactionCount, locations, windowStart,
                windowEnd, createdAt, null);
    }

    public FraudAlert withRiskScore(Double riskScore) {
        return new FraudAlert(id, accountId, transactionId, rule, severity, score, reason, transactionCount, locations,
                windowStart, windowEnd, createdAt, riskScore);
    }

    public static final String RULE_VELOCITY_MULTI_LOCATION = "VELOCITY_MULTI_LOCATION";
    public static final String RULE_HIGH_VALUE = "HIGH_VALUE";
    public static final String RULE_BLOCKLIST = "BLOCKLIST_HIT";
}
