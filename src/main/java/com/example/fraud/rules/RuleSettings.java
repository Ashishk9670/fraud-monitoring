package com.example.fraud.rules;

import com.example.fraud.config.FraudRulesProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/** API view of the effective rule parameters. */
public record RuleSettings(int windowSeconds, int maxTransactions, int minDistinctLocations,
                           BigDecimal highValueAmount, Instant updatedAt, String updatedBy) {

    public static RuleSettings from(FraudRulesProperties p) {
        return new RuleSettings((int) p.window().toSeconds(), p.maxTransactions(), p.minDistinctLocations(),
                p.highValueAmount(), null, null);
    }

    public FraudRulesProperties toProperties() {
        return new FraudRulesProperties(Duration.ofSeconds(windowSeconds), maxTransactions, minDistinctLocations,
                highValueAmount);
    }
}
