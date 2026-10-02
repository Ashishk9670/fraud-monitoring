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
        Instant createdAt) {

    public static final String RULE_VELOCITY_MULTI_LOCATION = "VELOCITY_MULTI_LOCATION";
    public static final String RULE_HIGH_VALUE = "HIGH_VALUE";
    public static final String RULE_BLOCKLIST = "BLOCKLIST_HIT";
}
