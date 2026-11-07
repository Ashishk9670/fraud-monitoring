package com.example.fraud.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exposes real consumer-group lag (end offset minus committed offset, per group and topic) as
 * {@code fraud_consumer_group_lag}. The client-side {@code kafka_consumer_fetch_manager_records_lag_max} cannot be used for
 * alerting: it measures how far the consumer has FETCHED into its in-memory buffer, so a consumer stuck in slow processing
 * still reports a tiny lag (observed: 151 reported while the real lag was about 5,900).
 */
@Component
public class ConsumerGroupLagMonitor {
    private static final Logger log = LoggerFactory.getLogger(ConsumerGroupLagMonitor.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final KafkaAdmin kafkaAdmin;
    private Admin admin;
    private final MeterRegistry registry;
    private final List<String> groups;
    private final Counter errors;
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public ConsumerGroupLagMonitor(KafkaAdmin kafkaAdmin, MeterRegistry registry,
                                   @Value("${fraud.lag.groups:analytics-sink,alert-sink,fraud-monitoring-streams}") List<String> groups) {
        this.kafkaAdmin = kafkaAdmin;
        this.registry = registry;
        this.groups = groups;
        this.errors = Counter.builder("fraud.consumer.group.lag.errors").description("Failed consumer-group lag lookups").register(registry);
    }

    /** Created on first use so that startup never depends on the brokers being reachable. */
    private synchronized Admin admin() {
        if (admin == null) admin = Admin.create(kafkaAdmin.getConfigurationProperties());
        return admin;
    }

    /** Test hook: use a stub instead of a real broker connection. */
    synchronized void useAdmin(Admin stub) {
        this.admin = stub;
    }

    @Scheduled(fixedDelayString = "${fraud.lag.poll-ms:15000}", initialDelayString = "${fraud.lag.poll-ms:15000}")
    void poll() {
        for (String group : groups) {
            try {
                refresh(group);
            } catch (Exception e) {
                errors.increment();
                log.debug("Could not read lag for group {}: {}", group, e.toString());
            }
        }
    }

    void refresh(String group) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committed = admin().listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (committed.isEmpty()) return;
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        committed.keySet().forEach(tp -> latest.put(tp, OffsetSpec.latest()));
        Map<TopicPartition, ListOffsetsResultInfo> ends = admin().listOffsets(latest).all().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        Map<TopicPartition, Long> endOffsets = new HashMap<>();
        ends.forEach((tp, info) -> endOffsets.put(tp, info.offset()));
        lagByTopic(committed, endOffsets).forEach((topic, lag) -> holder(group, topic).set(lag));
    }

    /** Sum over partitions of (end - committed), never negative; partitions without a committed offset are skipped. */
    static Map<String, Long> lagByTopic(Map<TopicPartition, OffsetAndMetadata> committed, Map<TopicPartition, Long> endOffsets) {
        Map<String, Long> byTopic = new HashMap<>();
        committed.forEach((tp, om) -> {
            Long end = endOffsets.get(tp);
            if (om == null || end == null) return;
            byTopic.merge(tp.topic(), Math.max(0, end - om.offset()), Long::sum);
        });
        return byTopic;
    }

    private AtomicLong holder(String group, String topic) {
        return gauges.computeIfAbsent(group + "|" + topic,
                k -> registry.gauge("fraud.consumer.group.lag", Tags.of("group", group, "topic", topic), new AtomicLong()));
    }

    @PreDestroy
    synchronized void close() {
        if (admin != null) admin.close(Duration.ofSeconds(2));
    }
}
