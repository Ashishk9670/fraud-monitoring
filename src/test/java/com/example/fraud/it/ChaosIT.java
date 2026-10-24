package com.example.fraud.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.fraud.redis.BlockType;
import com.example.fraud.redis.BlocklistService;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Chaos / resilience: the app talks to Redis and Kafka only through Toxiproxy, which is cut mid-run and restored.
 * Order matters: baseline, Redis outage + recovery, Kafka outage + recovery. Needs Docker ({@code mvn verify}).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChaosIT {
    private static final int REDIS_PROXY_PORT = 8666;
    private static final int KAFKA_PROXY_PORT = 8667;

    static Network network;
    static ToxiproxyContainer toxiproxy;
    static GenericContainer<?> kafka;
    static GenericContainer<?> redis;
    static PostgreSQLContainer<?> postgres;
    static Proxy redisProxy;
    static Proxy kafkaProxy;
    static boolean started;

    static synchronized void startAll() throws Exception {
        if (started) return;
        network = Network.newNetwork();
        toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
                .withNetwork(network).withNetworkAliases("toxiproxy");
        toxiproxy.start();
        String advertised = toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(KAFKA_PROXY_PORT);

        // Single-node KRaft broker; clients reach it only via the proxy, so that is the advertised address.
        kafka = new GenericContainer<>("apache/kafka:3.8.0")
                .withNetwork(network).withNetworkAliases("kafka")
                .withEnv("KAFKA_NODE_ID", "1")
                .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9094,CONTROLLER://:9093")
                .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://" + advertised + ",BROKER://localhost:9094")
                .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                        "PLAINTEXT:PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT")
                .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER")
                .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
        kafka.start();

        redis = new GenericContainer<>("redis:7-alpine").withNetwork(network).withNetworkAliases("redis")
                .withExposedPorts(6379);
        redis.start();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        redisProxy = client.createProxy("redis", "0.0.0.0:" + REDIS_PROXY_PORT, "redis:6379");
        kafkaProxy = client.createProxy("kafka", "0.0.0.0:" + KAFKA_PROXY_PORT, "kafka:9092");
        started = true;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        if (!DockerClientFactory.instance().isDockerAvailable()) return;
        startAll();
        r.add("spring.kafka.bootstrap-servers", () -> toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(KAFKA_PROXY_PORT));
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", toxiproxy::getHost);
        r.add("spring.data.redis.port", () -> toxiproxy.getMappedPort(REDIS_PROXY_PORT));
        r.add("analytics.store", () -> "memory");
        r.add("spring.kafka.streams.application-id", () -> "chaos-" + System.nanoTime());
        r.add("spring.kafka.streams.state-dir", () -> System.getProperty("java.io.tmpdir") + "/fraud-chaos-" + System.nanoTime());
    }

    @AfterAll
    static void tearDown() {
        // Containers are reaped by Ryuk; just make sure nothing stays cut.
        try {
            if (redisProxy != null) redisProxy.enable();
            if (kafkaProxy != null) kafkaProxy.enable();
        } catch (Exception ignored) {
            // best effort
        }
    }

    @LocalServerPort int port;
    @Autowired BlocklistService blocklist;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private static io.restassured.specification.RequestSpecification api() {
        return given().header("X-API-Key", "dev-local-key");
    }

    private void burst(String account) {
        api().queryParam("accountId", account).queryParam("count", 12)
                .post("/api/simulator/fraud-burst").then().statusCode(202);
    }

    private List<String> caseAccounts() {
        return api().contentType(ContentType.JSON)
                .body(Map.of("query", "{ cases(limit: 200) { accountId } }"))
                .post("/graphql").then().statusCode(200).extract().path("data.cases.accountId");
    }

    private void awaitCase(String account, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(caseAccounts()).contains(account));
    }

    private void assertAlive() {
        given().get("/actuator/health/liveness").then().statusCode(200);
    }

    @Test
    @Order(1)
    void baselineBurstProducesCase() {
        String account = "chaos-base-" + UUID.randomUUID();
        burst(account);
        awaitCase(account, Duration.ofSeconds(120));
    }

    @Test
    @Order(2)
    void redisOutageDegradesGracefullyAndRecovers() throws Exception {
        redisProxy.disable();
        try {
            String account = "chaos-redis-" + UUID.randomUUID();
            // Redis side effects and blocklist lookups fail fast; detection must keep working.
            burst(account);
            awaitCase(account, Duration.ofSeconds(120));
            assertAlive();
            // Direct Redis access fails fast (rejected) rather than hanging.
            long t0 = System.nanoTime();
            try {
                blocklist.isBlocked(BlockType.ACCOUNT, account);
            } catch (RuntimeException expected) {
                // fine: surfaced as an error, not a hang
            }
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(10));
        } finally {
            redisProxy.enable();
        }

        // Recovery: Redis-backed features work again without restarting the app.
        String blocked = "chaos-blocked-" + UUID.randomUUID();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            blocklist.block(BlockType.ACCOUNT, blocked, "chaos", Duration.ofMinutes(5));
            assertThat(blocklist.isBlocked(BlockType.ACCOUNT, blocked)).isTrue();
        });
        String account = "chaos-redis-after-" + UUID.randomUUID();
        burst(account);
        awaitCase(account, Duration.ofSeconds(120));
    }

    @Test
    @Order(3)
    void kafkaOutageDegradesGracefullyAndRecovers() throws Exception {
        kafkaProxy.disable();
        try {
            Thread.sleep(5_000);
            // The API process stays up and Postgres-backed reads keep working while the broker is unreachable.
            assertAlive();
            assertThat(caseAccounts()).isNotNull();
            // Ingestion must not hang the request thread.
            long t0 = System.nanoTime();
            api().contentType(ContentType.JSON).body(Map.of("accountId", "chaos-kafka", "merchantId", "m",
                            "amount", 10, "currency", "USD", "location", "US"))
                    .post("/api/transactions").then().statusCode(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is(202), org.hamcrest.Matchers.greaterThanOrEqualTo(500)));
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(30));
        } finally {
            kafkaProxy.enable();
        }

        // Recovery: Streams and the producer reconnect on their own and detection resumes.
        String account = "chaos-kafka-after-" + UUID.randomUUID();
        await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            burst(account);
            assertThat(caseAccounts()).contains(account);
        });
        assertAlive();
    }
}
