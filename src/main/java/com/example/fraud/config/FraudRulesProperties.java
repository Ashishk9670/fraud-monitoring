package com.example.fraud.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "fraud.rules")
public record FraudRulesProperties(
        @DefaultValue("PT1M") Duration window,
        @DefaultValue("5") int maxTransactions,
        @DefaultValue("2") int minDistinctLocations,
        @DefaultValue("10000") BigDecimal highValueAmount) {

    public static FraudRulesProperties defaults() {
        return new FraudRulesProperties(Duration.ofMinutes(1), 5, 2, new BigDecimal("10000"));
    }
}
