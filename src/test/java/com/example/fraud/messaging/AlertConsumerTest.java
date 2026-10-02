package com.example.fraud.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.fraud.analytics.AnalyticsStore;
import com.example.fraud.cases.CaseService;
import com.example.fraud.json.Json;
import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AlertConsumerTest {
    private FraudAlert alert() {
        return new FraudAlert("al-1", "a1", null, "R", Severity.HIGH, 0.9, "why", 6, List.of("US", "GB"),
                Instant.now(), Instant.now(), Instant.now());
    }

    @Test
    void opensCaseIndexesAndBroadcasts() throws Exception {
        CaseService cases = mock(CaseService.class);
        AnalyticsStore analytics = mock(AnalyticsStore.class);
        AlertBroadcaster broadcaster = new AlertBroadcaster();
        List<FraudAlert> received = new ArrayList<>();
        broadcaster.stream().subscribe(received::add);

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        new AlertConsumer(cases, analytics, broadcaster, metrics).onAlert(Json.MAPPER.writeValueAsString(alert()));

        assertThat(metrics.counter("fraud.alerts", "rule", "R", "severity", "HIGH").count()).isEqualTo(1.0);
        verify(cases).openCase(any());
        verify(analytics).indexAlert(any());
        assertThat(received).hasSize(1);
        assertThat(received.get(0).id()).isEqualTo("al-1");
    }

    @Test
    void analyticsFailureDoesNotPreventBroadcast() throws Exception {
        CaseService cases = mock(CaseService.class);
        AnalyticsStore analytics = mock(AnalyticsStore.class);
        doThrow(new RuntimeException("clickhouse down")).when(analytics).indexAlert(any());
        AlertBroadcaster broadcaster = new AlertBroadcaster();
        List<FraudAlert> received = new ArrayList<>();
        broadcaster.stream().subscribe(received::add);

        new AlertConsumer(cases, analytics, broadcaster, new SimpleMeterRegistry()).onAlert(Json.MAPPER.writeValueAsString(alert()));
        assertThat(received).hasSize(1);
    }

    @Test
    void malformedMessageIsSkipped() {
        CaseService cases = mock(CaseService.class);
        new AlertConsumer(cases, mock(AnalyticsStore.class), new AlertBroadcaster(), new SimpleMeterRegistry()).onAlert("not json");
        verify(cases, never()).openCase(any());
    }
}
