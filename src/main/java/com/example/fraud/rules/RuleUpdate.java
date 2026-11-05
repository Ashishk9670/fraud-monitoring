package com.example.fraud.rules;

import java.math.BigDecimal;

/** Partial update: null fields keep their current value. Bounds are enforced by {@link RuleValidator}. */
public record RuleUpdate(Integer windowSeconds, Integer maxTransactions, Integer minDistinctLocations,
                         BigDecimal highValueAmount) {}
