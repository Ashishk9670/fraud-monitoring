package com.example.fraud.support;

import com.example.fraud.FraudMonitoringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;

/**
 * Runs the whole app without Docker (embedded Kafka, H2, in-memory analytics, fake Redis) on port 8080.
 * Used for local Playwright runs: {@code make e2e-local-app}.
 */
public final class StandaloneTestApp {
    private StandaloneTestApp() {}

    public static void main(String[] args) {
        EmbeddedKafkaBroker broker = new EmbeddedKafkaZKBroker(1, true, 1, "transactions", "fraud-alerts");
        broker.afterPropertiesSet();
        new SpringApplicationBuilder(FraudMonitoringApplication.class, TestInfra.class)
                .profiles("test")
                .properties("spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                        "server.port=" + System.getProperty("server.port", "8080"))
                .run(args);
    }
}
