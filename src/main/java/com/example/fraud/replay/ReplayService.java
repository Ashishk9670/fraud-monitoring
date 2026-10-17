package com.example.fraud.replay;

import com.example.fraud.analytics.AnalyticsStore;
import com.example.fraud.config.Topics;
import com.example.fraud.json.Json;
import com.example.fraud.model.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Re-feeds historical transactions from the analytics store into the replay topic. The replay pipeline applies the
 * current rules in isolation (own state, own output topic, no Redis side effects), so replays are safe to run against
 * a live system, e.g. to see what a rule change would have flagged.
 */
@Service
public class ReplayService {
    static final Duration MAX_RANGE = Duration.ofDays(7);
    static final int DEFAULT_LIMIT = 10_000;
    static final int MAX_LIMIT = 100_000;

    private final AnalyticsStore analytics;
    private final KafkaTemplate<String, String> kafka;
    private final ReplayAlertCollector collector;

    public ReplayService(AnalyticsStore analytics, KafkaTemplate<String, String> kafka,
                         ReplayAlertCollector collector) {
        this.analytics = analytics;
        this.kafka = kafka;
        this.collector = collector;
    }

    public ReplayRun start(ReplayRequest req) {
        if (req == null || req.from() == null || req.to() == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (!req.to().isAfter(req.from())) throw new IllegalArgumentException("to must be after from");
        if (Duration.between(req.from(), req.to()).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("range must not exceed " + MAX_RANGE.toDays() + " days");
        }
        int limit = req.limit() == null ? DEFAULT_LIMIT : req.limit();
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);

        List<Transaction> txns = analytics.transactionsBetween(req.from(), req.to(), limit);
        String id = UUID.randomUUID().toString().substring(0, 8);
        // Register before publishing so alerts arriving immediately are attributed to this run.
        collector.register(new ReplayRun(id, req.from(), req.to(), txns.size(), Instant.now()));
        try {
            for (Transaction t : txns) {
                kafka.send(Topics.TRANSACTIONS_REPLAY, id + "|" + t.accountId(), Json.MAPPER.writeValueAsString(t));
            }
            kafka.flush();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish replay " + id, e);
        }
        return collector.run(id);
    }
}
