package com.example.fraud.messaging;

import com.example.fraud.config.Topics;
import com.example.fraud.json.Json;
import com.example.fraud.model.Transaction;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Publishes transactions keyed by accountId so each account's events land on one partition. */
@Service
public class TransactionPublisher {
    private final KafkaTemplate<String, String> kafka;

    public TransactionPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void publish(Transaction t) {
        try {
            kafka.send(Topics.TRANSACTIONS, t.accountId(), Json.MAPPER.writeValueAsString(t));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish transaction " + t.id(), e);
        }
    }
}
