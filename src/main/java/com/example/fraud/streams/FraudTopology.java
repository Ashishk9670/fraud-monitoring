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
import com.example.fraud.rules.RulesProvider;
import com.example.fraud.scoring.RiskScorer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.Stores;
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

    /** Window state is retained this long; the maximum configurable rule window must stay below it. */
    static final Duration STATS_RETENTION = Duration.ofHours(24);

    public static KStream<String, FraudAlert> build(StreamsBuilder builder, FraudRulesProperties rules,
                                                    BlocklistService blocklist, VelocityCounter velocity,
                                                    FeatureStore features) {
        return build(builder, RulesProvider.fixed(rules), blocklist, velocity, features);
    }

    public static KStream<String, FraudAlert> build(StreamsBuilder builder, RulesProvider rules,
                                                    BlocklistService blocklist, VelocityCounter velocity,
                                                    FeatureStore features) {
        return build(builder, rules, blocklist, velocity, features, RiskScorer.none());
    }

    public static KStream<String, FraudAlert> build(StreamsBuilder builder, RulesProvider rules,
                                                    BlocklistService blocklist, VelocityCounter velocity,
                                                    FeatureStore features, RiskScorer scorer) {
        Function<Transaction, Double> risk = t -> riskOf(scorer, features, t);
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

        // Rule 1: windowed aggregation. The window size is read per record from the rules provider, so a rule change
        // takes effect without restarting the topology (see WindowedVelocityProcessor).
        builder.addStateStore(Stores.windowStoreBuilder(
                Stores.persistentWindowStore(STATS_STORE, STATS_RETENTION, Duration.ofMinutes(1), false),
                Serdes.String(), statsSerde));
        KStream<String, FraudAlert> windowed = txns.process(
                () -> new WindowedVelocityProcessor(rules, STATS_STORE, risk), STATS_STORE);

        // Rule 2: high value.
        KStream<String, FraudAlert> highValue = txns
                .filter((k, t) -> t.amount().compareTo(rules.current().highValueAmount()) >= 0)
                .mapValues(t -> single(t, FraudAlert.RULE_HIGH_VALUE, Severity.MEDIUM, 0.6,
                        "Transaction amount %s %s >= %s".formatted(t.amount(), t.currency(),
                                rules.current().highValueAmount()), risk.apply(t)));

        // Rule 3: blocklist.
        KStream<String, FraudAlert> blocked = txns
                .filter((k, t) -> isBlocked(blocklist, t))
                .mapValues(t -> single(t, FraudAlert.RULE_BLOCKLIST, Severity.CRITICAL, 1.0,
                        "Account %s or merchant %s is on the blocklist".formatted(t.accountId(), t.merchantId()),
                        risk.apply(t)));

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

    /** Best-effort risk score; a failing scorer or feature store never breaks the stream. */
    private static Double riskOf(RiskScorer scorer, FeatureStore features, Transaction t) {
        try {
            var s = scorer.score(t, features.get(t.accountId()));
            return s.isPresent() ? s.getAsDouble() : null;
        } catch (RuntimeException e) {
            log.warn("Risk scoring failed for txn {}: {}", t.id(), e.toString());
            return null;
        }
    }

    private static FraudAlert single(Transaction t, String rule, Severity sev, double score, String reason,
                                     Double riskScore) {
        return new FraudAlert(UUID.randomUUID().toString(), t.accountId(), t.id(), rule, sev, score, reason,
                1, List.of(t.location()), t.timestamp(), t.timestamp(), Instant.now(), riskScore);
    }
}
