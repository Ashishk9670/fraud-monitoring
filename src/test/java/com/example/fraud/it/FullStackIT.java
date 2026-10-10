package com.example.fraud.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.fraud.analytics.AnalyticsStore;
import com.example.fraud.redis.BlockType;
import com.example.fraud.redis.BlocklistService;
import com.example.fraud.redis.FeatureStore;
import com.example.fraud.redis.VelocityCounter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real Kafka + Redis + Postgres + ClickHouse. Run with {@code mvn verify} (needs Docker). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullStackIT extends ContainersBase {
    @LocalServerPort int port;
    @Autowired BlocklistService blocklist;
    @Autowired VelocityCounter velocity;
    @Autowired FeatureStore features;
    @Autowired AnalyticsStore analytics;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void redisBlocklistVelocityAndFeatureStoreWork() {
        blocklist.block(BlockType.ACCOUNT, "it-acct", "r", Duration.ofMinutes(5));
        assertThat(blocklist.isBlocked(BlockType.ACCOUNT, "it-acct")).isTrue();
        blocklist.unblock(BlockType.ACCOUNT, "it-acct");
        assertThat(blocklist.isBlocked(BlockType.ACCOUNT, "it-acct")).isFalse();

        velocity.increment("v-acct");
        assertThat(velocity.increment("v-acct")).containsEntry("1m", 2L);
        assertThat(velocity.current("v-acct")).containsEntry("1h", 2L);
    }

    @Test
    void fraudBurstEndToEndLandsInPostgresClickHouseAndGraphQl() {
        String account = "it-" + UUID.randomUUID();
        given().header("X-API-Key", "dev-local-key").queryParam("accountId", account).queryParam("count", 12)
                .post("/api/simulator/fraud-burst").then().statusCode(202);

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            List<String> accounts = given().header("X-API-Key", "dev-local-key").contentType(ContentType.JSON)
                    .body(Map.of("query", "{ cases(limit: 100) { accountId } }"))
                    .post("/graphql").then().statusCode(200).extract().path("data.cases.accountId");
            assertThat(accounts).contains(account);
        });
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(analytics.recentAlerts(100)).extracting(a -> a.accountId()).contains(account));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(analytics.alertCountsByRule(Duration.ofHours(1))).isNotEmpty());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(features.get(account)).containsKey("txn_count"));
    }
}
