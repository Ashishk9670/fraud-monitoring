package com.example.fraud.streams;

import com.example.fraud.config.FraudRulesProperties;
import com.example.fraud.config.Topics;
import com.example.fraud.json.Json;
import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Severity;
import com.example.fraud.model.Transaction;
import com.example.fraud.redis.BlockType;
import com.example.fraud.redis.BlocklistService;
import com.example.fraud.redis.FeatureStore;
import com.example.fraud.redis.VelocityCounter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fraud detection topology. Input records on {@code transactions} must be keyed by accountId.
 *
 * <ul>
 *   <li>Windowed rule: more than N transactions inside the window from at least M distinct locations.</li>
 *   <li>High value rule: single transaction at/above a threshold.</li>
 *   <li>Blocklist rule: account or merchant present in the (Redis-backed) blocklist.</li>
 * </ul>
 * Side effects (velocity counters, feature store) go through interfaces so the topology is testable without Redis.
 */
public final class FraudTopology {
    private static final Logger log = LoggerFactory.getLogger(FraudTopology.class);
    public static final String STATS_STORE = "txn-stats-window";

    private FraudTopology() {}

    public static KStream<String, FraudAlert> build(StreamsBuilder builder, FraudRulesProperties rules,
                                                    BlocklistService blocklist, VelocityCounter velocity,
                                                    FeatureStore features) {
        Serde<Transaction> txSerde = Json.serde(Transaction.class);
        Serde<FraudAlert> alertSerde = Json.serde(FraudAlert.class);
        Serde<TxnStats> statsSerde = Json.serde(TxnStats.class);

        KStream<String, Transaction> txns = builder
                .stream(Topics.TRANSACTIONS, Consumed.with(Serdes.String(), txSerde)
                        .withTimestampExtractor(new TransactionTimestampExtractor()))
                .filter((k, v) -> v != null && v.accountId() != null);

        // Redis side effects: velocity counters + feature store (best effort, never kill the stream thread).
        txns.foreach((k, t) -> {
            try {
                velocity.increment(t.accountId());
                features.update(t);
            } catch (RuntimeException e) {
                log.warn("Redis side-effect failed for txn {}: {}", t.id(), e.toString());
            }
        });

        // Rule 1: windowed aggregation.
        KStream<String, FraudAlert> windowed = txns
                .groupByKey(Grouped.with(Serdes.String(), txSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(rules.window()))
                .aggregate(TxnStats::empty,
                        (acct, t, s) -> s.add(t, rules.maxTransactions(), rules.minDistinctLocations()),
                        Materialized.<String, TxnStats, WindowStore<Bytes, byte[]>>as(STATS_STORE)
                                .withKeySerde(Serdes.String()).withValueSerde(statsSerde))
                .toStream()
                .filter((w, s) -> s != null && s.justTriggered())
                .map((w, s) -> KeyValue.pair(w.key(), new FraudAlert(
                        UUID.randomUUID().toString(), w.key(), null,
                        FraudAlert.RULE_VELOCITY_MULTI_LOCATION, Severity.HIGH,
                        Math.min(1.0, s.count() / (2.0 * rules.maxTransactions())),
                        "%d transactions in %s from %d locations %s".formatted(
                                s.count(), rules.window(), s.locations().size(), s.locations()),
                        s.count(), List.copyOf(s.locations()),
                        Instant.ofEpochMilli(w.window().start()), Instant.ofEpochMilli(w.window().end()),
                        Instant.now())));

        // Rule 2: high value.
        KStream<String, FraudAlert> highValue = txns
                .filter((k, t) -> t.amount().compareTo(rules.highValueAmount()) >= 0)
                .mapValues(t -> single(t, FraudAlert.RULE_HIGH_VALUE, Severity.MEDIUM, 0.6,
                        "Transaction amount %s %s >= %s".formatted(t.amount(), t.currency(), rules.highValueAmount())));

        // Rule 3: blocklist.
        KStream<String, FraudAlert> blocked = txns
                .filter((k, t) -> isBlocked(blocklist, t))
                .mapValues(t -> single(t, FraudAlert.RULE_BLOCKLIST, Severity.CRITICAL, 1.0,
                        "Account %s or merchant %s is on the blocklist".formatted(t.accountId(), t.merchantId())));

        KStream<String, FraudAlert> all = windowed.merge(highValue).merge(blocked);
        all.to(Topics.FRAUD_ALERTS, Produced.with(Serdes.String(), alertSerde));
        return all;
    }

    private static boolean isBlocked(BlocklistService blocklist, Transaction t) {
        try {
            return blocklist.isBlocked(BlockType.ACCOUNT, t.accountId())
                    || blocklist.isBlocked(BlockType.MERCHANT, t.merchantId())
                    || blocklist.isBlocked(BlockType.LOCATION, t.location());
        } catch (RuntimeException e) {
            log.warn("Blocklist lookup failed: {}", e.toString());
            return false;
        }
    }

    private static FraudAlert single(Transaction t, String rule, Severity sev, double score, String reason) {
        return new FraudAlert(UUID.randomUUID().toString(), t.accountId(), t.id(), rule, sev, score, reason,
                1, List.of(t.location()), t.timestamp(), t.timestamp(), Instant.now());
    }
}
