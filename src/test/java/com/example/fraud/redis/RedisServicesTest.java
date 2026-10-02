package com.example.fraud.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.fraud.model.Transaction;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings({"unchecked", "rawtypes"})
class RedisServicesTest {
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    HashOperations hash;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        hash = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hash);
    }

    @Test
    void blocklistWithTtlUsesExpiringSet() {
        new RedisBlocklistService(redis).block(BlockType.ACCOUNT, "a1", "mule", Duration.ofHours(1));
        verify(values).set("blocklist:account:a1", "mule", Duration.ofHours(1));
    }

    @Test
    void blocklistWithoutTtlIsPermanent() {
        new RedisBlocklistService(redis).block(BlockType.MERCHANT, "m1", null, null);
        verify(values).set("blocklist:merchant:m1", "blocked");
    }

    @Test
    void isBlockedChecksKeyExistence() {
        when(redis.hasKey("blocklist:account:a1")).thenReturn(true);
        var svc = new RedisBlocklistService(redis);
        assertThat(svc.isBlocked(BlockType.ACCOUNT, "a1")).isTrue();
        assertThat(svc.isBlocked(BlockType.ACCOUNT, "other")).isFalse();
        assertThat(svc.isBlocked(BlockType.ACCOUNT, null)).isFalse();
    }

    @Test
    void unblockDeletesKey() {
        new RedisBlocklistService(redis).unblock(BlockType.LOCATION, "RU");
        verify(redis).delete("blocklist:location:RU");
    }

    @Test
    void velocitySetsTtlOnlyOnFirstIncrement() {
        when(values.increment(any())).thenReturn(1L);
        var counts = new RedisVelocityCounter(redis).increment("a1");
        assertThat(counts).containsEntry("1m", 1L).containsEntry("1h", 1L);
        verify(redis).expire(any(), eq(Duration.ofMinutes(1)));
        verify(redis).expire(any(), eq(Duration.ofHours(1)));

        org.mockito.Mockito.clearInvocations(redis);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(any())).thenReturn(7L);
        new RedisVelocityCounter(redis).increment("a1");
        verify(redis, never()).expire(any(), any(Duration.class));
    }

    @Test
    void featureStoreWritesFeaturesAndRefreshesTtl() {
        var t = new Transaction("t1", "a1", "m", new BigDecimal("10.50"), "USD", "GB", null);
        new RedisFeatureStore(redis, Duration.ofHours(2)).update(t);
        verify(hash).increment("features:a1", "txn_count", 1);
        verify(hash).put("features:a1", "last_location", "GB");
        verify(redis).expire("features:a1", Duration.ofHours(2));
    }
}
