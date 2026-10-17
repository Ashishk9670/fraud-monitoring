package com.example.fraud.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.fraud.analytics.InMemoryAnalyticsStore;
import com.example.fraud.config.Topics;
import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Severity;
import com.example.fraud.model.Transaction;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class ReplayServiceTest {
    static final Instant T0 = Instant.parse("2025-03-01T10:00:00Z");

    InMemoryAnalyticsStore analytics = new InMemoryAnalyticsStore();
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    ReplayAlertCollector collector = new ReplayAlertCollector();
    ReplayService service;

    @BeforeEach
    void setUp() {
        service = new ReplayService(analytics, kafka, collector);
        for (int i = 0; i < 5; i++) {
            analytics.indexTransaction(new Transaction("t" + i, "acct-" + (i % 2), "m", new BigDecimal("10"), "USD",
                    "US", T0.plusSeconds(i * 60L)));
        }
    }

    @Test
    void publishesTransactionsInRangeToTheReplayTopicKeyedByRunAndAccount() {
        ReplayRun run = service.start(new ReplayRequest(T0.plusSeconds(60), T0.plusSeconds(240), null));
        assertThat(run.transactions()).isEqualTo(3); // t1, t2, t3 ("to" is exclusive)

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(3)).send(eq(Topics.TRANSACTIONS_REPLAY), key.capture(), anyString());
        assertThat(key.getAllValues()).allMatch(k -> k.startsWith(run.id() + "|acct-"));
        verify(kafka).flush();
        assertThat(collector.run(run.id())).isEqualTo(run);
    }

    @Test
    void honoursLimitAndOrdersOldestFirst() {
        ReplayRun run = service.start(new ReplayRequest(T0, T0.plus(Duration.ofHours(1)), 2));
        assertThat(run.transactions()).isEqualTo(2);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2)).send(anyString(), anyString(), body.capture());
        assertThat(body.getAllValues().get(0)).contains("\"t0\"");
        assertThat(body.getAllValues().get(1)).contains("\"t1\"");
    }

    @Test
    void rejectsInvalidRequests() {
        assertThatThrownBy(() -> service.start(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(new ReplayRequest(null, T0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(new ReplayRequest(T0, T0, null)))
                .hasMessageContaining("after");
        assertThatThrownBy(() -> service.start(new ReplayRequest(T0, T0.plus(Duration.ofDays(8)), null)))
                .hasMessageContaining("days");
        assertThatThrownBy(() -> service.start(new ReplayRequest(T0, T0.plusSeconds(1), 0)))
                .hasMessageContaining("limit");
        verify(kafka, never()).send(anyString(), anyString(), anyString());
        verify(kafka, never()).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }

    @Test
    void collectorAttributesAlertsToRunsAndIsBounded() {
        ReplayRun a = service.start(new ReplayRequest(T0, T0.plusSeconds(1), null));
        FraudAlert alert = new FraudAlert("x", "acct-0", null, "R", Severity.HIGH, 0.5, "why", 1, List.of("US"), T0,
                T0, T0);
        collector.add(a.id(), alert);
        collector.add("unknown", alert);
        assertThat(collector.alerts(a.id())).containsExactly(alert);
        assertThat(collector.alerts("unknown")).isEmpty();

        for (int i = 0; i < ReplayAlertCollector.MAX_RUNS + 1; i++) {
            service.start(new ReplayRequest(T0, T0.plusSeconds(1), null));
        }
        assertThat(collector.run(a.id())).isNull(); // oldest run evicted
    }

    @Test
    void collectorParsesKeyedKafkaRecords() throws Exception {
        ReplayRun a = service.start(new ReplayRequest(T0, T0.plusSeconds(1), null));
        FraudAlert alert = new FraudAlert("x", "acct-0", null, "R", Severity.HIGH, 0.5, "why", 1, List.of("US"), T0,
                T0, T0);
        String json = com.example.fraud.json.Json.MAPPER.writeValueAsString(alert);
        collector.onReplayAlert(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                Topics.FRAUD_ALERTS_REPLAY, 0, 0, a.id() + "|acct-0", json));
        collector.onReplayAlert(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                Topics.FRAUD_ALERTS_REPLAY, 0, 1, "no-separator", json));
        collector.onReplayAlert(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                Topics.FRAUD_ALERTS_REPLAY, 0, 2, a.id() + "|acct-0", "{not json"));
        assertThat(collector.alerts(a.id())).hasSize(1);
    }
}
