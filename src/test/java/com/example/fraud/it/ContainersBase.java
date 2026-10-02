package com.example.fraud.it;

import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Shared real infrastructure (Kafka, Redis, Postgres, ClickHouse). Tests are skipped when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
public abstract class ContainersBase {
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:24.8")
            .withEnv("CLICKHOUSE_DB", "fraud").withEnv("CLICKHOUSE_USER", "fraud")
            .withEnv("CLICKHOUSE_PASSWORD", "fraud").withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).withStartupTimeout(Duration.ofMinutes(2)));

    static boolean started;

    static synchronized void startAll() {
        if (started) return;
        KAFKA.start();
        POSTGRES.start();
        REDIS.start();
        CLICKHOUSE.start();
        started = true;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        if (!org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) return;
        startAll();
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("analytics.store", () -> "clickhouse");
        r.add("analytics.clickhouse.url", () -> "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        r.add("spring.kafka.streams.application-id", () -> "it-" + System.nanoTime());
        r.add("spring.kafka.streams.state-dir", () -> System.getProperty("java.io.tmpdir") + "/fraud-it-" + System.nanoTime());
    }
}
