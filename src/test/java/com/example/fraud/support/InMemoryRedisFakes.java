package com.example.fraud.support;

import com.example.fraud.model.Transaction;
import com.example.fraud.redis.BlockType;
import com.example.fraud.redis.BlocklistService;
import com.example.fraud.redis.FeatureStore;
import com.example.fraud.redis.VelocityCounter;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Simple in-memory stand-ins for the Redis-backed services (used where Docker is not available). */
public final class InMemoryRedisFakes {
    private InMemoryRedisFakes() {}

    public static class Blocklist implements BlocklistService {
        private final Set<String> entries = ConcurrentHashMap.newKeySet();

        @Override public boolean isBlocked(BlockType type, String value) { return entries.contains(type + ":" + value); }
        @Override public void block(BlockType type, String value, String reason, Duration ttl) { entries.add(type + ":" + value); }
        @Override public void unblock(BlockType type, String value) { entries.remove(type + ":" + value); }
    }

    public static class Velocity implements VelocityCounter {
        private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

        @Override public Map<String, Long> increment(String accountId) {
            long n = counts.computeIfAbsent(accountId, k -> new AtomicLong()).incrementAndGet();
            return Map.of("1m", n, "1h", n);
        }
        @Override public Map<String, Long> current(String accountId) {
            long n = counts.getOrDefault(accountId, new AtomicLong()).get();
            return new LinkedHashMap<>(Map.of("1m", n, "1h", n));
        }
    }

    public static class Features implements FeatureStore {
        private final Map<String, Map<String, String>> data = new ConcurrentHashMap<>();

        @Override public synchronized void update(Transaction t) {
            Map<String, String> m = data.computeIfAbsent(t.accountId(), k -> new ConcurrentHashMap<>());
            m.merge("txn_count", "1", (a, b) -> String.valueOf(Long.parseLong(a) + 1));
            double x = t.amount().doubleValue();
            m.merge("total_amount", String.valueOf(x), (a, b) -> String.valueOf(Double.parseDouble(a) + x));
            m.merge("total_amount_sq", String.valueOf(x * x), (a, b) -> String.valueOf(Double.parseDouble(a) + x * x));
            m.put("last_location", t.location());
        }
        @Override public Map<String, String> get(String accountId) { return new HashMap<>(data.getOrDefault(accountId, Map.of())); }
    }
}
