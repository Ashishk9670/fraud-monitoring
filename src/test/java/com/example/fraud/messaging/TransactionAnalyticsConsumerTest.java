package com.example.fraud.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fraud.analytics.AnalyticsStore;
import com.example.fraud.analytics.InMemoryAnalyticsStore;
import com.example.fraud.json.Json;
import com.example.fraud.model.Transaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionAnalyticsConsumerTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static String json(String id) throws Exception {
        return Json.MAPPER.writeValueAsString(new Transaction(id, "acct-1", "m", new BigDecimal("10.00"), "USD", "US", Instant.parse("2026-01-01T00:00:00Z")));
    }

    private double dropped() {
        return registry.get("fraud.analytics.index.dropped").counter().count();
    }

    /** Records how it was called: per-record inserts are what did not scale. */
    static class RecordingStore extends InMemoryAnalyticsStore {
        final List<Integer> batchSizes = new ArrayList<>();
        int singleCalls;

        @Override
        public void indexTransaction(Transaction t) {
            singleCalls++;
            super.indexTransaction(t);
        }

        @Override
        public void indexTransactions(List<Transaction> batch) {
            batchSizes.add(batch.size());
            super.indexTransactions(batch);
        }
    }

    @Test
    void aWholePollIsWrittenWithOneBatchCall() throws Exception {
        var store = new RecordingStore();
        var consumer = new TransactionAnalyticsConsumer(store, registry);
        List<String> poll = new ArrayList<>();
        for (int i = 0; i < 250; i++) poll.add(json("t" + i));
        consumer.onTransactions(poll);
        assertThat(store.batchSizes).containsExactly(250);
        assertThat(store.transactionCount()).isEqualTo(250);
        assertThat(dropped()).isZero();
    }

    @Test
    void anUnparseableRecordIsCountedAndDoesNotBlockTheRestOfTheBatch() throws Exception {
        var store = new RecordingStore();
        var consumer = new TransactionAnalyticsConsumer(store, registry);
        consumer.onTransactions(List.of(json("a"), "not json {", json("b")));
        assertThat(store.batchSizes).containsExactly(2);
        assertThat(dropped()).isEqualTo(1.0);
    }

    @Test
    void aStoreFailureIsCountedPerDroppedTransactionAndNotThrown() throws Exception {
        AnalyticsStore failing = new InMemoryAnalyticsStore() {
            @Override
            public void indexTransactions(List<Transaction> batch) {
                throw new IllegalStateException("clickhouse down");
            }
        };
        var consumer = new TransactionAnalyticsConsumer(failing, registry);
        consumer.onTransactions(List.of(json("a"), json("b"), json("c")));
        assertThat(dropped()).isEqualTo(3.0);
    }

    @Test
    void anEmptyOrAllInvalidPollNeverCallsTheStore() {
        var store = new RecordingStore();
        var consumer = new TransactionAnalyticsConsumer(store, registry);
        consumer.onTransactions(List.of("junk", "more junk"));
        assertThat(store.batchSizes).isEmpty();
        assertThat(dropped()).isEqualTo(2.0);
    }

    @Test
    void theDropCounterExistsAtZeroBeforeAnyEvent() {
        new TransactionAnalyticsConsumer(new InMemoryAnalyticsStore(), registry);
        assertThat(dropped()).isZero();
    }
}
