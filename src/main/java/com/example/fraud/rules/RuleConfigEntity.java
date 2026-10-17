package com.example.fraud.rules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "fraud_rule_config")
public class RuleConfigEntity {
    public static final int SINGLETON_ID = 1;

    @Id
    private int id;
    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds;
    @Column(name = "max_transactions", nullable = false)
    private int maxTransactions;
    @Column(name = "min_distinct_locations", nullable = false)
    private int minDistinctLocations;
    @Column(name = "high_value_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal highValueAmount;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    protected RuleConfigEntity() {}

    public static RuleConfigEntity of(RuleSettings s, String by) {
        RuleConfigEntity e = new RuleConfigEntity();
        e.id = SINGLETON_ID;
        e.apply(s, by);
        return e;
    }

    public void apply(RuleSettings s, String by) {
        this.windowSeconds = s.windowSeconds();
        this.maxTransactions = s.maxTransactions();
        this.minDistinctLocations = s.minDistinctLocations();
        this.highValueAmount = s.highValueAmount();
        this.updatedAt = Instant.now();
        this.updatedBy = by;
    }

    public RuleSettings toSettings() {
        return new RuleSettings(windowSeconds, maxTransactions, minDistinctLocations, highValueAmount, updatedAt,
                updatedBy);
    }
}
