package com.example.fraud.support;

import com.example.fraud.redis.BlocklistService;
import com.example.fraud.redis.FeatureStore;
import com.example.fraud.redis.VelocityCounter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the Redis-backed services with in-memory fakes for the Docker-free API tests. */
@TestConfiguration
public class TestInfra {
    @Bean @Primary BlocklistService fakeBlocklist() { return new InMemoryRedisFakes.Blocklist(); }
    @Bean @Primary VelocityCounter fakeVelocity() { return new InMemoryRedisFakes.Velocity(); }
    @Bean @Primary FeatureStore fakeFeatures() { return new InMemoryRedisFakes.Features(); }
}
