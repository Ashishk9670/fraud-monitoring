package com.example.fraud.graphql;

import com.example.fraud.analytics.AccountRisk;
import com.example.fraud.analytics.AnalyticsStore;
import com.example.fraud.analytics.RuleCount;
import com.example.fraud.cases.CaseService;
import com.example.fraud.cases.CaseStatus;
import com.example.fraud.cases.FraudCase;
import com.example.fraud.messaging.AlertBroadcaster;
import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Severity;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

@Controller
public class FraudGraphQlController {
    private final CaseService cases;
    private final AnalyticsStore analytics;
    private final AlertBroadcaster broadcaster;

    public FraudGraphQlController(CaseService cases, AnalyticsStore analytics, AlertBroadcaster broadcaster) {
        this.cases = cases;
        this.analytics = analytics;
        this.broadcaster = broadcaster;
    }

    private static int clamp(Integer limit, int dflt) {
        return limit == null ? dflt : Math.max(1, Math.min(limit, 500));
    }

    @QueryMapping
    public List<FraudCase> cases(@Argument CaseStatus status, @Argument Integer limit) {
        return cases.list(status, clamp(limit, 50));
    }

    @QueryMapping("case")
    public FraudCase caseById(@Argument UUID id) {
        return cases.find(id).orElse(null);
    }

    @QueryMapping
    public List<FraudAlert> alerts(@Argument Integer limit) {
        return analytics.recentAlerts(clamp(limit, 50));
    }

    @QueryMapping
    public List<RuleCount> alertCountsByRule(@Argument Integer sinceMinutes) {
        return analytics.alertCountsByRule(Duration.ofMinutes(sinceMinutes == null ? 60 : sinceMinutes));
    }

    @QueryMapping
    public List<AccountRisk> topRiskAccounts(@Argument Integer sinceMinutes, @Argument Integer limit) {
        return analytics.topRiskAccounts(Duration.ofMinutes(sinceMinutes == null ? 60 : sinceMinutes),
                clamp(limit, 10));
    }

    @MutationMapping
    public FraudCase updateCase(@Argument UUID id, @Argument CaseStatus status, @Argument String assignee,
                                @Argument String notes) {
        return cases.update(id, status, assignee, notes);
    }

    @SubscriptionMapping
    public Flux<FraudAlert> alertStream(@Argument Severity minSeverity) {
        return broadcaster.stream()
                .filter(a -> minSeverity == null || a.severity().ordinal() >= minSeverity.ordinal());
    }
}
