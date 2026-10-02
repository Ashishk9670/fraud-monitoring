package com.example.fraud.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Fixed-bucket counters: key {@code velocity:<label>:<account>:<bucket>} with TTL = window. */
@Service
public class RedisVelocityCounter implements VelocityCounter {
    static final Map<String, Duration> WINDOWS = Map.of("1m", Duration.ofMinutes(1), "1h", Duration.ofHours(1));

    private final StringRedisTemplate redis;
    private final Clock clock = Clock.systemUTC();

    public RedisVelocityCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String label, Duration w, String accountId) {
        long bucket = clock.millis() / w.toMillis();
        return "velocity:" + label + ":" + accountId + ":" + bucket;
    }

    @Override
    public Map<String, Long> increment(String accountId) {
        Map<String, Long> out = new LinkedHashMap<>();
        WINDOWS.forEach((label, w) -> {
            String k = key(label, w, accountId);
            Long n = redis.opsForValue().increment(k);
            if (n != null && n == 1L) redis.expire(k, w);
            out.put(label, n == null ? 0L : n);
        });
        return out;
    }

    @Override
    public Map<String, Long> current(String accountId) {
        Map<String, Long> out = new LinkedHashMap<>();
        WINDOWS.forEach((label, w) -> {
            String v = redis.opsForValue().get(key(label, w, accountId));
            out.put(label, v == null ? 0L : Long.parseLong(v));
        });
        return out;
    }
}
