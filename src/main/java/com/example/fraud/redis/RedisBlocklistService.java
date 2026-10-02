package com.example.fraud.redis;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Blocklist entries are plain keys {@code blocklist:<type>:<value>} so each can carry its own TTL. */
@Service
public class RedisBlocklistService implements BlocklistService {
    private final StringRedisTemplate redis;

    public RedisBlocklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(BlockType type, String value) {
        return "blocklist:" + type.name().toLowerCase() + ":" + value;
    }

    @Override
    public boolean isBlocked(BlockType type, String value) {
        return value != null && Boolean.TRUE.equals(redis.hasKey(key(type, value)));
    }

    @Override
    public void block(BlockType type, String value, String reason, Duration ttl) {
        String k = key(type, value);
        String v = reason == null ? "blocked" : reason;
        if (ttl == null) redis.opsForValue().set(k, v);
        else redis.opsForValue().set(k, v, ttl);
    }

    @Override
    public void unblock(BlockType type, String value) {
        redis.delete(key(type, value));
    }
}
