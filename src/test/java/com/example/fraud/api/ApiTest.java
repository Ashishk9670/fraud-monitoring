package com.example.fraud.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import com.example.fraud.support.TestInfra;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** Full-stack API tests (REST + GraphQL + Kafka Streams) with no Docker: embedded Kafka, H2, fakes for Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"transactions", "fraud-alerts"})
@ActiveProfiles("test")
@Import(TestInfra.class)
@DirtiesContext
class ApiTest {
    @LocalServerPort int port;
    @Autowired ExecutionGraphQlService graphQlService;
    @Autowired org.springframework.kafka.config.StreamsBuilderFactoryBean streams;
    GraphQlTester graphQl;
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        graphQl = ExecutionGraphQlServiceTester.create(graphQlService);
        await().atMost(Duration.ofSeconds(60)).until(() ->
                streams.getKafkaStreams() != null
                        && streams.getKafkaStreams().state() == org.apache.kafka.streams.KafkaStreams.State.RUNNING);
    }

    private static io.restassured.specification.RequestSpecification api() {
        return given().header("X-API-Key", "test-key");
    }

    private String burst(String account, int count) {
        api().queryParam("accountId", account).queryParam("count", count)
                .post("/api/simulator/fraud-burst").then().statusCode(202);
        return account;
    }

    @Test
    void ingestValidTransactionIsAccepted() {
        api().contentType(ContentType.JSON).body(Map.of(
                        "accountId", "rest-1", "merchantId", "m", "amount", 12.5, "currency", "USD", "location", "US"))
                .post("/api/transactions")
                .then().statusCode(202).body("status", equalTo("ACCEPTED"));
    }

    @Test
    void ingestInvalidTransactionIsRejected() {
        api().contentType(ContentType.JSON).body(Map.of("accountId", "", "amount", -1))
                .post("/api/transactions").then().statusCode(400);
    }

    @Test
    void blocklistLifecycleOverRest() {
        api().contentType(ContentType.JSON).body(Map.of("type", "ACCOUNT", "value", "bl-1", "reason", "test"))
                .post("/api/blocklist").then().statusCode(201);
        api().get("/api/blocklist/ACCOUNT/bl-1").then().statusCode(200).body("blocked", equalTo(true));
        api().delete("/api/blocklist/ACCOUNT/bl-1").then().statusCode(204);
        api().get("/api/blocklist/ACCOUNT/bl-1").then().body("blocked", equalTo(false));
    }

    @Test
    void fraudBurstFlowsThroughKafkaStreamsIntoCasesAlertsAndAnalytics() {
        String account = burst("flow-" + UUID.randomUUID(), 12);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<String> accounts = graphQl.document("{ cases(limit: 100) { accountId rule severity status } }")
                    .execute().path("cases[*].accountId").entityList(String.class).get();
            assertThat(accounts).contains(account);
        });

        graphQl.document("{ cases(limit: 100) { accountId rule severity status } }").execute()
                .path("cases[?(@.accountId=='" + account + "')].rule").entityList(String.class)
                .satisfies(rules -> assertThat(rules).contains("VELOCITY_MULTI_LOCATION"));

        graphQl.document("{ alerts(limit: 100) { accountId rule locations } }").execute()
                .path("alerts[*].accountId").entityList(String.class)
                .satisfies(a -> assertThat(a).contains(account));

        graphQl.document("{ alertCountsByRule { rule count } }").execute()
                .path("alertCountsByRule[*].rule").entityList(String.class)
                .satisfies(r -> assertThat(r).contains("VELOCITY_MULTI_LOCATION"));

        graphQl.document("{ topRiskAccounts { accountId alertCount } }").execute()
                .path("topRiskAccounts").entityList(Map.class).satisfies(l -> assertThat(l).isNotEmpty());

        // Redis-backed features were updated by the topology (fake here).
        api().get("/api/accounts/" + account + "/features").then().statusCode(200)
                .body("features.txn_count", equalTo("12"));
    }

    @Test
    void caseCanBeReviewedViaMutation() {
        String account = burst("review-" + UUID.randomUUID(), 12);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(graphQl.document("{ cases(limit: 100) { accountId } }").execute()
                        .path("cases[*].accountId").entityList(String.class).get()).contains(account));

        String id = graphQl.document("{ cases(limit: 100) { id accountId } }").execute()
                .path("cases[?(@.accountId=='" + account + "')].id").entityList(String.class).get().get(0);

        graphQl.document("mutation($id: ID!) { updateCase(id: $id, status: CONFIRMED_FRAUD, assignee: \"alice\", notes: \"n\") { status assignee notes } }")
                .variable("id", id).execute()
                .path("updateCase.status").entity(String.class).isEqualTo("CONFIRMED_FRAUD");

        graphQl.document("query($id: ID!) { case(id: $id) { status assignee } }").variable("id", id).execute()
                .path("case.assignee").entity(String.class).isEqualTo("alice");

        graphQl.document("{ cases(status: CONFIRMED_FRAUD, limit: 100) { id } }").execute()
                .path("cases[*].id").entityList(String.class).satisfies(ids -> assertThat(ids).contains(id));
    }

    @Test
    void unknownCaseReturnsNull() {
        graphQl.document("query($id: ID!) { case(id: $id) { id } }").variable("id", UUID.randomUUID().toString())
                .execute().path("case").valueIsNull();
    }

    @Test
    void blocklistedAccountProducesCriticalAlert() {
        String account = "blocked-" + UUID.randomUUID();
        api().contentType(ContentType.JSON).body(Map.of("type", "ACCOUNT", "value", account))
                .post("/api/blocklist").then().statusCode(201);
        api().contentType(ContentType.JSON).body(Map.of(
                        "accountId", account, "merchantId", "m", "amount", 5, "currency", "USD", "location", "US"))
                .post("/api/transactions").then().statusCode(202);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(graphQl.document("{ cases(limit: 100) { accountId rule severity } }").execute()
                        .path("cases[?(@.accountId=='" + account + "')].severity").entityList(String.class).get())
                        .contains("CRITICAL"));
    }

    @Test
    void liveAlertsAreStreamedOverGraphQlSse() throws Exception {
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .header("X-API-Key", "test-key")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"query\":\"subscription { alertStream { accountId rule severity } }\"}"))
                .build();
        Thread reader = new Thread(() -> {
            try {
                HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (var br = new BufferedReader(new InputStreamReader(resp.body()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("data:")) received.add(om.readTree(line.substring(5).trim()));
                    }
                }
            } catch (Exception ignored) {
                // connection closed at test end
            }
        });
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(1500); // let the subscription attach before producing alerts

        String account = burst("sse-" + UUID.randomUUID(), 12);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(received.stream().map(n -> n.path("data").path("alertStream").path("accountId").asText()))
                        .contains(account));
        reader.interrupt();
    }

    @Test
    void subscriptionRespectsMinSeverityFilter() {
        String account = "hv-" + UUID.randomUUID();
        var flux = graphQl.document("subscription { alertStream(minSeverity: HIGH) { accountId rule severity } }")
                .executeSubscription().toFlux("alertStream.accountId", String.class);
        var sub = flux.collectList().toFuture();
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { }

        // HIGH_VALUE alert is MEDIUM -> filtered out; the burst alert is HIGH -> delivered.
        api().contentType(ContentType.JSON).body(Map.of(
                        "accountId", account, "merchantId", "m", "amount", 50000, "currency", "USD", "location", "US"))
                .post("/api/transactions").then().statusCode(202);
        String burstAccount = burst("sev-" + UUID.randomUUID(), 12);
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            Thread.sleep(500);
            return graphQl.document("{ alerts(limit: 100) { accountId } }").execute()
                    .path("alerts[*].accountId").entityList(String.class).get().contains(burstAccount);
        });
        sub.cancel(true);
    }

    @Test
    void apiRequiresAnApiKey() {
        given().contentType(ContentType.JSON).body(Map.of("accountId", "a")).post("/api/transactions").then().statusCode(401);
        given().header("X-API-Key", "wrong").get("/api/blocklist/ACCOUNT/x").then().statusCode(401);
        given().contentType(ContentType.JSON).body(Map.of("query", "{ cases { id } }")).post("/graphql").then().statusCode(401);
        given().get("/actuator/prometheus").then().statusCode(401);
    }

    @Test
    void bearerTokenIsAcceptedAsApiKey() {
        given().header("Authorization", "Bearer test-key").get("/api/blocklist/ACCOUNT/x").then().statusCode(200);
        given().header("Authorization", "Bearer test-key").contentType(ContentType.JSON)
                .body(Map.of("query", "{ cases(limit: 1) { id } }")).post("/graphql").then().statusCode(200);
    }

    @Test
    void oversizedBodyAndBatchAreRejected() {
        String big = "x".repeat(300 * 1024);
        api().contentType(ContentType.JSON).body("{\"accountId\":\"" + big + "\"}")
                .post("/api/transactions").then().statusCode(413);
        List<Map<String, Object>> batch = java.util.stream.IntStream.range(0, 501).mapToObj(i -> Map.<String, Object>of(
                "accountId", "b", "merchantId", "m", "amount", 1, "currency", "USD", "location", "US")).toList();
        api().contentType(ContentType.JSON).body(batch).post("/api/transactions/batch").then().statusCode(413);
    }

    @Test
    void fieldLengthAndAmountLimitsAreValidated() {
        api().contentType(ContentType.JSON).body(Map.of("accountId", "a".repeat(65), "merchantId", "m",
                "amount", 5, "currency", "USD", "location", "US")).post("/api/transactions").then().statusCode(400);
        api().contentType(ContentType.JSON).body(Map.of("accountId", "a", "merchantId", "m",
                "amount", 2_000_000_000L, "currency", "USD", "location", "US")).post("/api/transactions").then().statusCode(400);
    }

    @Test
    void deepGraphQlQueryIsRejected() {
        String deep = "{ cases { " + "a { ".repeat(10) + "x" + " }".repeat(10) + " } }";
        api().contentType(ContentType.JSON).body(Map.of("query", deep)).post("/graphql")
                .then().statusCode(200).body("errors", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void alertMetricsAreExposedInPrometheusFormat() {
        String account = burst("metrics-" + UUID.randomUUID(), 12);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(api().get("/actuator/prometheus").then().statusCode(200).extract().asString())
                        .contains("fraud_alerts_total{").contains("rule=\"VELOCITY_MULTI_LOCATION\"")
                        .contains("fraud_transactions_published_total"));
    }

    @Test
    void healthEndpointIsUp() {
        given().get("/actuator/health/liveness").then().statusCode(200);
    }

    @Test
    void dashboardIsServed() {
        given().get("/").then().statusCode(200).body(org.hamcrest.Matchers.containsString("Fraud Analyst Dashboard"));
    }
}
