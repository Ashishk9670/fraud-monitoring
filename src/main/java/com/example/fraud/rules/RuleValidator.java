package com.example.fraud.rules;

import java.math.BigDecimal;

/** Pure validation/merge logic for rule updates. */
public final class RuleValidator {
    public static final int MAX_WINDOW_SECONDS = 3600;
    public static final int MAX_TRANSACTIONS = 100_000;
    public static final int MAX_LOCATIONS = 1000;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    private RuleValidator() {}

    /** Applies {@code u} on top of {@code base}; throws {@link IllegalArgumentException} when out of bounds. */
    public static RuleSettings merge(RuleSettings base, RuleUpdate u) {
        int window = u.windowSeconds() != null ? u.windowSeconds() : base.windowSeconds();
        int max = u.maxTransactions() != null ? u.maxTransactions() : base.maxTransactions();
        int locs = u.minDistinctLocations() != null ? u.minDistinctLocations() : base.minDistinctLocations();
        BigDecimal amount = u.highValueAmount() != null ? u.highValueAmount() : base.highValueAmount();
        require(window >= 1 && window <= MAX_WINDOW_SECONDS, "windowSeconds must be between 1 and " + MAX_WINDOW_SECONDS);
        require(max >= 1 && max <= MAX_TRANSACTIONS, "maxTransactions must be between 1 and " + MAX_TRANSACTIONS);
        require(locs >= 1 && locs <= MAX_LOCATIONS, "minDistinctLocations must be between 1 and " + MAX_LOCATIONS);
        require(amount.signum() > 0 && amount.compareTo(MAX_AMOUNT) <= 0,
                "highValueAmount must be greater than 0 and at most " + MAX_AMOUNT.toPlainString());
        return new RuleSettings(window, max, locs, amount, base.updatedAt(), base.updatedBy());
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
