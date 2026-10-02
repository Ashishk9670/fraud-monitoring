package com.example.fraud.messaging;

import com.example.fraud.analytics.AnalyticsStore;
import com.example.fraud.config.Topics;
import com.example.fraud.json.Json;
import com.example.fraud.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionAnalyticsConsumer {
    private static final Logger log = LoggerFactory.getLogger(TransactionAnalyticsConsumer.class);
    private final AnalyticsStore analytics;

    public TransactionAnalyticsConsumer(AnalyticsStore analytics) {
        this.analytics = analytics;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "analytics-sink")
    public void onTransaction(String json) {
        try {
            analytics.indexTransaction(Json.MAPPER.readValue(json, Transaction.class));
        } catch (Exception e) {
            log.warn("Could not index transaction: {}", e.toString());
        }
    }
}
