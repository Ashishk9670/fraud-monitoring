package com.example.fraud.analytics;

import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Transaction;
import java.time.Duration;
import java.util.List;

/** Analytics backend abstraction (ClickHouse by default; in-memory for local/test use). */
public interface AnalyticsStore {
    void indexTransaction(Transaction t);

    void indexAlert(FraudAlert a);

    List<FraudAlert> recentAlerts(int limit);

    List<RuleCount> alertCountsByRule(Duration since);

    List<AccountRisk> topRiskAccounts(Duration since, int limit);
}
