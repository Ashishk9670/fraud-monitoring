package com.example.fraud.streams;

import com.example.fraud.config.FraudRulesProperties;
import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Severity;
import com.example.fraud.model.Transaction;
import com.example.fraud.rules.RulesProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.WindowStore;

/**
 * Velocity / multi-location rule over epoch-aligned tumbling windows whose size is resolved per record from the
 * {@link RulesProvider}. Per-window stats live in a window store keyed by (account, window start), so changing the
 * window size only affects windows opened afterwards.
 */
final class WindowedVelocityProcessor implements Processor<String, Transaction, String, FraudAlert> {
    private final RulesProvider rules;
    private final String storeName;
    private ProcessorContext<String, FraudAlert> context;
    private WindowStore<String, TxnStats> store;

    WindowedVelocityProcessor(RulesProvider rules, String storeName) {
        this.rules = rules;
        this.storeName = storeName;
    }

    @Override
    public void init(ProcessorContext<String, FraudAlert> context) {
        this.context = context;
        this.store = context.getStateStore(storeName);
    }

    @Override
    public void process(Record<String, Transaction> record) {
        Transaction t = record.value();
        FraudRulesProperties r = rules.current();
        long sizeMs = r.window().toMillis();
        long start = WindowMath.windowStart(record.timestamp(), sizeMs);

        TxnStats previous = store.fetch(record.key(), start);
        TxnStats next = (previous == null ? TxnStats.empty() : previous)
                .add(t, r.maxTransactions(), r.minDistinctLocations());
        store.put(record.key(), next, start);

        if (next.justTriggered()) {
            FraudAlert alert = new FraudAlert(UUID.randomUUID().toString(), record.key(), null,
                    FraudAlert.RULE_VELOCITY_MULTI_LOCATION, Severity.HIGH,
                    Math.min(1.0, next.count() / (2.0 * r.maxTransactions())),
                    "%d transactions in %s from %d locations %s".formatted(
                            next.count(), r.window(), next.locations().size(), next.locations()),
                    next.count(), List.copyOf(next.locations()),
                    Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + sizeMs), Instant.now());
            context.forward(record.withValue(alert));
        }
    }
}
