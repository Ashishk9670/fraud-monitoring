package com.example.fraud.redis;

import com.example.fraud.model.Transaction;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Per-account feature hash {@code features:<account>} that expires after a period of inactivity. */
@Service
public class RedisFeatureStore implements FeatureStore {
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisFeatureStore(StringRedisTemplate redis, @Value("${fraud.redis.feature-ttl:PT24H}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public void update(Transaction t) {
        String key = "features:" + t.accountId();
        var h = redis.opsForHash();
        h.increment(key, "txn_count", 1);
        h.increment(key, "total_amount", t.amount().doubleValue());
        h.increment(key, "total_amount_sq", t.amount().doubleValue() * t.amount().doubleValue());
        h.put(key, "last_location", t.location());
        h.put(key, "last_amount", t.amount().toPlainString());
        h.put(key, "last_seen", t.timestamp().toString());
        redis.expire(key, ttl);
    }

    @Override
    public Map<String, String> get(String accountId) {
        Map<String, String> out = new HashMap<>();
        redis.opsForHash().entries("features:" + accountId).forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }
}
