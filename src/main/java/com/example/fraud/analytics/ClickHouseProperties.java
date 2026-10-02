package com.example.fraud.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "analytics.clickhouse")
public record ClickHouseProperties(
        @DefaultValue("http://localhost:8123") String url,
        @DefaultValue("fraud") String user,
        @DefaultValue("fraud") String password,
        @DefaultValue("fraud") String database) {}
