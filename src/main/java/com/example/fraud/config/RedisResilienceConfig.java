package com.example.fraud.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fail fast when Redis is unreachable: commands issued while disconnected are rejected immediately (instead of being
 * queued until the command timeout) so the stream thread and API calls degrade rather than stall. The client keeps
 * reconnecting in the background and recovers by itself.
 */
@Configuration
public class RedisResilienceConfig {

    @Bean
    LettuceClientConfigurationBuilderCustomizer redisFailFast() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                .build());
    }
}
