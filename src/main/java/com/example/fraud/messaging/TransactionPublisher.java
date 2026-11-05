package com.example.fraud.messaging;

import com.example.fraud.config.Topics;
import com.example.fraud.json.Json;
import com.example.fraud.model.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Publishes transactions keyed by accountId so each account's events land on one partition. */
@Service
public class TransactionPublisher {
    private final KafkaTemplate<String, String> kafka;

    private final Counter published;
    private final Counter failed;

    public TransactionPublisher(KafkaTemplate<String, String> kafka, MeterRegistry metrics) {
        this.kafka = kafka;
        this.published = Counter.builder("fraud.transactions.published")
                .description("Transactions published to Kafka").register(metrics);
        this.failed = Counter.builder("fraud.transactions.publish.failed")
                .description("Transactions Kafka did not acknowledge (e.g. broker unreachable)").register(metrics);
    }

    public void publish(Transaction t) {
        try {
            kafka.send(Topics.TRANSACTIONS, t.accountId(), Json.MAPPER.writeValueAsString(t))
                    .whenComplete((r, ex) -> {
                        if (ex != null) failed.increment();
                    });
            published.increment();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish transaction " + t.id(), e);
        }
    }
}
