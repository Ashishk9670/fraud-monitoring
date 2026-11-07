package com.example.fraud.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

class ConsumerGroupLagMonitorTest {
    private static final TopicPartition P0 = new TopicPartition("transactions", 0);
    private static final TopicPartition P1 = new TopicPartition("transactions", 1);
    private static final TopicPartition ALERTS = new TopicPartition("fraud-alerts", 0);

    private static OffsetAndMetadata at(long o) {
        return new OffsetAndMetadata(o);
    }

    @Test
    void lagIsEndMinusCommittedSummedPerTopicAndNeverNegative() {
        var committed = Map.of(P0, at(1272), P1, at(602), ALERTS, at(5));
        var ends = Map.of(P0, 6135L, P1, 6279L, ALERTS, 3L);   // fraud-alerts: committed beyond end (e.g. topic recreated)
        var lag = ConsumerGroupLagMonitor.lagByTopic(committed, ends);
        assertThat(lag).containsEntry("transactions", (6135L - 1272) + (6279L - 602)).containsEntry("fraud-alerts", 0L);
    }

    @Test
    void partitionsWithoutACommittedOrEndOffsetAreSkipped() {
        var committed = new java.util.HashMap<TopicPartition, OffsetAndMetadata>();
        committed.put(P0, null);
        committed.put(P1, at(10));
        assertThat(ConsumerGroupLagMonitor.lagByTopic(committed, Map.of(P0, 100L))).isEmpty();
    }

    private static Admin adminWith(Map<TopicPartition, OffsetAndMetadata> committed, Map<TopicPartition, Long> ends) {
        Admin admin = mock(Admin.class);
        var groupResult = mock(ListConsumerGroupOffsetsResult.class);
        when(groupResult.partitionsToOffsetAndMetadata()).thenReturn(KafkaFuture.completedFuture(committed));
        when(admin.listConsumerGroupOffsets(anyString())).thenReturn(groupResult);
        var endResult = mock(ListOffsetsResult.class);
        Map<TopicPartition, ListOffsetsResultInfo> infos = new java.util.HashMap<>();
        ends.forEach((tp, o) -> infos.put(tp, new ListOffsetsResultInfo(o, 0L, Optional.empty())));
        when(endResult.all()).thenReturn(KafkaFuture.completedFuture(infos));
        when(admin.listOffsets(any(Map.class))).thenReturn(endResult);
        return admin;
    }

    private static ConsumerGroupLagMonitor monitorWith(Admin admin, SimpleMeterRegistry registry) {
        var monitor = new ConsumerGroupLagMonitor(mock(org.springframework.kafka.core.KafkaAdmin.class), registry, List.of("analytics-sink"));
        monitor.useAdmin(admin);
        return monitor;
    }

    @Test
    void pollPublishesAGaugePerGroupAndTopic() {
        var registry = new SimpleMeterRegistry();
        var monitor = monitorWith(adminWith(Map.of(P0, at(100)), Map.of(P0, 5100L)), registry);
        monitor.poll();
        assertThat(registry.get("fraud.consumer.group.lag").tag("group", "analytics-sink").tag("topic", "transactions").gauge().value())
                .isEqualTo(5000.0);
    }

    @Test
    void aFailingLookupIsCountedAndKeepsTheLastKnownValue() throws Exception {
        var registry = new SimpleMeterRegistry();
        Admin admin = adminWith(Map.of(P0, at(100)), Map.of(P0, 600L));
        var monitor = monitorWith(admin, registry);
        monitor.poll();
        var failing = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> boom = new org.apache.kafka.common.internals.KafkaFutureImpl<>();
        ((org.apache.kafka.common.internals.KafkaFutureImpl<Map<TopicPartition, OffsetAndMetadata>>) boom).completeExceptionally(new TimeoutException("broker down"));
        when(failing.partitionsToOffsetAndMetadata()).thenReturn(boom);
        when(admin.listConsumerGroupOffsets(anyString())).thenReturn(failing);
        monitor.poll();
        assertThat(registry.get("fraud.consumer.group.lag.errors").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("fraud.consumer.group.lag").gauge().value()).isEqualTo(500.0);
    }
}
