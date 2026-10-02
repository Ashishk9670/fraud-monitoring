package com.example.fraud.analytics;

import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "analytics.store", havingValue = "memory")
public class InMemoryAnalyticsStore implements AnalyticsStore {
    private final List<FraudAlert> alerts = new CopyOnWriteArrayList<>();
    private final List<Transaction> transactions = new CopyOnWriteArrayList<>();

    @Override
    public void indexTransaction(Transaction t) {
        transactions.add(t);
    }

    public int transactionCount() {
        return transactions.size();
    }

    @Override
    public void indexAlert(FraudAlert a) {
        alerts.add(a);
    }

    @Override
    public List<FraudAlert> recentAlerts(int limit) {
        return alerts.stream().sorted(Comparator.comparing(FraudAlert::createdAt).reversed()).limit(limit).toList();
    }

    @Override
    public List<RuleCount> alertCountsByRule(Duration since) {
        Instant cutoff = Instant.now().minus(since);
        return alerts.stream().filter(a -> a.createdAt().isAfter(cutoff))
                .collect(Collectors.groupingBy(FraudAlert::rule, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new RuleCount(e.getKey(), e.getValue().intValue()))
                .sorted(Comparator.comparingInt(RuleCount::count).reversed()).toList();
    }

    @Override
    public List<AccountRisk> topRiskAccounts(Duration since, int limit) {
        Instant cutoff = Instant.now().minus(since);
        return alerts.stream().filter(a -> a.createdAt().isAfter(cutoff))
                .collect(Collectors.groupingBy(FraudAlert::accountId)).entrySet().stream()
                .map(e -> new AccountRisk(e.getKey(), e.getValue().size(),
                        e.getValue().stream().mapToDouble(FraudAlert::score).max().orElse(0)))
                .sorted(Comparator.comparingInt(AccountRisk::alertCount).reversed()).limit(limit).toList();
    }
}
