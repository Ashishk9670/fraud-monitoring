package com.example.fraud.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** API authentication and request-size limits ({@code fraud.security.*}). */
@ConfigurationProperties(prefix = "fraud.security")
public record SecurityProperties(
        /** Accepted API keys (comma separated in env). Sent as {@code X-API-Key} or {@code Authorization: Bearer}. */
        @DefaultValue List<String> apiKeys,
        /** Max accepted request body for /api and /graphql. */
        @DefaultValue("256KB") DataSize maxRequestSize,
        /** Max transactions in one /api/transactions/batch call. */
        @DefaultValue("500") int maxBatchSize,
        /** Max GraphQL selection depth. */
        @DefaultValue("8") int maxGraphQlDepth) {
}
