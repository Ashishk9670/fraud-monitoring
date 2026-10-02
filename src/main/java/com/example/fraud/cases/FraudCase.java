package com.example.fraud.cases;

import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_cases")
public class FraudCase {
    @Id
    private UUID id;
    @Column(name = "alert_id", nullable = false, unique = true, length = 64)
    private String alertId;
    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;
    @Column(nullable = false, length = 64)
    private String rule;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;
    @Column(nullable = false)
    private double score;
    @Column(nullable = false, length = 2000)
    private String reason;
    @Column(name = "transaction_count", nullable = false)
    private int transactionCount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CaseStatus status;
    @Column(length = 128)
    private String assignee;
    @Column(length = 2000)
    private String notes;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FraudCase() {}

    public static FraudCase from(FraudAlert a) {
        FraudCase c = new FraudCase();
        c.id = UUID.randomUUID();
        c.alertId = a.id();
        c.accountId = a.accountId();
        c.rule = a.rule();
        c.severity = a.severity();
        c.score = a.score();
        c.reason = a.reason().length() > 2000 ? a.reason().substring(0, 2000) : a.reason();
        c.transactionCount = a.transactionCount();
        c.status = CaseStatus.OPEN;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return c;
    }

    public void update(CaseStatus status, String assignee, String notes) {
        if (status != null) this.status = status;
        if (assignee != null) this.assignee = assignee;
        if (notes != null) this.notes = notes;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAlertId() { return alertId; }
    public String getAccountId() { return accountId; }
    public String getRule() { return rule; }
    public Severity getSeverity() { return severity; }
    public double getScore() { return score; }
    public String getReason() { return reason; }
    public int getTransactionCount() { return transactionCount; }
    public CaseStatus getStatus() { return status; }
    public String getAssignee() { return assignee; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
