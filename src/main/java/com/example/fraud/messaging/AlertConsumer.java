package com.example.fraud.messaging;

import com.example.fraud.analytics.AnalyticsStore;
import com.example.fraud.cases.CaseService;
import com.example.fraud.config.Topics;
import com.example.fraud.json.Json;
import com.example.fraud.model.FraudAlert;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes the alerts topic: opens a case (Postgres), indexes into analytics, pushes to subscribers. */
@Component
public class AlertConsumer {
    private static final Logger log = LoggerFactory.getLogger(AlertConsumer.class);
    private final CaseService cases;
    private final AnalyticsStore analytics;
    private final AlertBroadcaster broadcaster;
    private final MeterRegistry metrics;

    public AlertConsumer(CaseService cases, AnalyticsStore analytics, AlertBroadcaster broadcaster,
                         MeterRegistry metrics) {
        this.cases = cases;
        this.analytics = analytics;
        this.broadcaster = broadcaster;
        this.metrics = metrics;
    }

    @KafkaListener(topics = Topics.FRAUD_ALERTS, groupId = "alert-sink")
    public void onAlert(String json) {
        FraudAlert alert;
        try {
            alert = Json.MAPPER.readValue(json, FraudAlert.class);
        } catch (Exception e) {
            log.warn("Skipping malformed alert: {}", e.toString());
            return;
        }
        handle(alert);
    }

    void handle(FraudAlert alert) {
        cases.openCase(alert);
        metrics.counter("fraud.alerts", "rule", alert.rule(), "severity", alert.severity().name()).increment();
        try {
            analytics.indexAlert(alert);
        } catch (RuntimeException e) {
            log.warn("Analytics indexing failed for alert {}: {}", alert.id(), e.toString());
        }
        broadcaster.publish(alert);
        recordLatency(alert);
    }

    /** End-to-end alert latency (SLO): triggering transaction time (window end) until the case is opened. */
    private void recordLatency(FraudAlert alert) {
        Instant from = alert.windowEnd() != null ? alert.windowEnd() : alert.createdAt();
        if (from == null) return;
        Duration d = Duration.between(from, Instant.now());
        io.micrometer.core.instrument.Timer.builder("fraud.alert.latency")
                .description("Time from the triggering transaction to the alert being processed into a case")
                .publishPercentileHistogram().register(metrics)
                .record(d.isNegative() ? Duration.ZERO : d);
    }
}
