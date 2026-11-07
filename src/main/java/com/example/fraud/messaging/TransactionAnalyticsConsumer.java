package com.example.fraud.messaging;

import com.example.fraud.analytics.AnalyticsStore;
import com.example.fraud.config.Topics;
import com.example.fraud.json.Json;
import com.example.fraud.model.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Copies transactions into the analytics store. Consumes in batches and writes each poll with ONE insert: the previous
 * one-request-per-record version fell thousands of records behind at about 170 tx/s (found with a k6 burst). Best effort by
 * design (analytics must never block detection), so failures are counted rather than retried; the counter makes loss visible.
 */
@Component
public class TransactionAnalyticsConsumer {
    private static final Logger log = LoggerFactory.getLogger(TransactionAnalyticsConsumer.class);
    private final AnalyticsStore analytics;
    private final Counter dropped;

    public TransactionAnalyticsConsumer(AnalyticsStore analytics, MeterRegistry registry) {
        this.analytics = analytics;
        // registered at zero so the first drop is visible to increase() in Prometheus
        this.dropped = Counter.builder("fraud.analytics.index.dropped").description("Transactions not written to the analytics store")
                .register(registry);
    }

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "analytics-sink", batch = "true")
    public void onTransactions(List<String> batch) {
        List<Transaction> parsed = new ArrayList<>(batch.size());
        for (String json : batch) {
            try {
                parsed.add(Json.MAPPER.readValue(json, Transaction.class));
            } catch (Exception e) {
                dropped.increment();
                log.warn("Could not parse transaction for analytics: {}", e.toString());
            }
        }
        if (parsed.isEmpty()) return;
        try {
            analytics.indexTransactions(parsed);
        } catch (Exception e) {
            dropped.increment(parsed.size());
            log.warn("Could not index {} transactions: {}", parsed.size(), e.toString());
        }
    }
}
