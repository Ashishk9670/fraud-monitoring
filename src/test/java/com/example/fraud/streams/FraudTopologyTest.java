package com.example.fraud.streams;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fraud.config.FraudRulesProperties;
import com.example.fraud.config.Topics;
import com.example.fraud.json.Json;
import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Severity;
import com.example.fraud.model.Transaction;
import com.example.fraud.redis.BlockType;
import com.example.fraud.support.InMemoryRedisFakes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FraudTopologyTest {
    static final Instant T0 = Instant.parse("2025-01-01T10:00:00Z");

    TopologyTestDriver driver;
    TestInputTopic<String, Transaction> input;
    TestOutputTopic<String, FraudAlert> output;
    InMemoryRedisFakes.Blocklist blocklist;
    InMemoryRedisFakes.Velocity velocity;
    InMemoryRedisFakes.Features features;

    @BeforeEach
    void setUp() {
        blocklist = new InMemoryRedisFakes.Blocklist();
        velocity = new InMemoryRedisFakes.Velocity();
        features = new InMemoryRedisFakes.Features();
        StreamsBuilder builder = new StreamsBuilder();
        FraudTopology.build(builder, FraudRulesProperties.defaults(), blocklist, velocity, features);

        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-test-" + System.nanoTime());
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        p.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        driver = new TopologyTestDriver(builder.build(), p);
        input = driver.createInputTopic(Topics.TRANSACTIONS, Serdes.String().serializer(),
                Json.serde(Transaction.class).serializer());
        output = driver.createOutputTopic(Topics.FRAUD_ALERTS, Serdes.String().deserializer(),
                Json.serde(FraudAlert.class).deserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private Transaction tx(String account, String location, String amount, Instant ts) {
        return new Transaction(null, account, "m-1", new BigDecimal(amount), "USD", location, ts);
    }

    private void send(Transaction t) {
        input.pipeInput(t.accountId(), t, t.timestamp());
    }

    @Test
    void alertsWhenMoreThanFiveTransactionsFromDifferentLocationsInOneMinute() {
        String[] locs = {"US", "GB", "IN", "US", "GB", "IN"};
        for (int i = 0; i < 6; i++) send(tx("a1", locs[i], "50.00", T0.plusSeconds(i * 5L)));

        List<FraudAlert> alerts = output.readValuesToList();
        assertThat(alerts).hasSize(1);
        FraudAlert a = alerts.get(0);
        assertThat(a.rule()).isEqualTo(FraudAlert.RULE_VELOCITY_MULTI_LOCATION);
        assertThat(a.accountId()).isEqualTo("a1");
        assertThat(a.severity()).isEqualTo(Severity.HIGH);
        assertThat(a.transactionCount()).isEqualTo(6);
        assertThat(a.locations()).containsExactlyInAnyOrder("US", "GB", "IN");
    }

    @Test
    void alertsOnlyOncePerWindowEvenIfMoreTransactionsFollow() {
        for (int i = 0; i < 10; i++) send(tx("a1", i % 2 == 0 ? "US" : "GB", "10.00", T0.plusSeconds(i)));
        assertThat(output.readValuesToList()).hasSize(1);
    }

    @Test
    void noAlertForFiveOrFewerTransactions() {
        String[] locs = {"US", "GB", "IN", "DE", "BR"};
        for (int i = 0; i < 5; i++) send(tx("a1", locs[i], "10.00", T0.plusSeconds(i)));
        assertThat(output.isEmpty()).isTrue();
    }

    @Test
    void noAlertWhenAllTransactionsFromSameLocation() {
        for (int i = 0; i < 8; i++) send(tx("a1", "US", "10.00", T0.plusSeconds(i)));
        assertThat(output.isEmpty()).isTrue();
    }

    @Test
    void noAlertWhenTransactionsSpreadAcrossWindows() {
        String[] locs = {"US", "GB", "IN"};
        for (int i = 0; i < 6; i++) send(tx("a1", locs[i % 3], "10.00", T0.plus(Duration.ofSeconds(i * 61L))));
        assertThat(output.isEmpty()).isTrue();
    }

    @Test
    void accountsAreAggregatedIndependently() {
        String[] locs = {"US", "GB", "IN"};
        for (int i = 0; i < 6; i++) send(tx(i % 2 == 0 ? "a1" : "a2", locs[i % 3], "10.00", T0.plusSeconds(i)));
        assertThat(output.isEmpty()).isTrue();
    }

    @Test
    void highValueTransactionRaisesAlert() {
        send(tx("a1", "US", "15000.00", T0));
        List<FraudAlert> alerts = output.readValuesToList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).rule()).isEqualTo(FraudAlert.RULE_HIGH_VALUE);
        assertThat(alerts.get(0).transactionId()).isNotNull();
    }

    @Test
    void blocklistedAccountRaisesCriticalAlert() {
        blocklist.block(BlockType.ACCOUNT, "bad-actor", "known mule", null);
        send(tx("bad-actor", "US", "10.00", T0));
        send(tx("good", "US", "10.00", T0));
        List<FraudAlert> alerts = output.readValuesToList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).rule()).isEqualTo(FraudAlert.RULE_BLOCKLIST);
        assertThat(alerts.get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(alerts.get(0).accountId()).isEqualTo("bad-actor");
    }

    @Test
    void blocklistedMerchantRaisesAlert() {
        blocklist.block(BlockType.MERCHANT, "m-1", null, null);
        send(tx("a1", "US", "10.00", T0));
        assertThat(output.readValuesToList()).extracting(FraudAlert::rule).containsExactly(FraudAlert.RULE_BLOCKLIST);
    }

    @Test
    void updatesVelocityCountersAndFeatureStore() {
        send(tx("a1", "US", "10.00", T0));
        send(tx("a1", "GB", "10.00", T0.plusSeconds(1)));
        assertThat(velocity.current("a1")).containsEntry("1m", 2L);
        assertThat(features.get("a1")).containsEntry("txn_count", "2").containsEntry("last_location", "GB");
    }

    @Test
    void redisFailureDoesNotBreakTheStream() {
        driver.close();
        StreamsBuilder builder = new StreamsBuilder();
        var failingVelocity = new com.example.fraud.redis.VelocityCounter() {
            public java.util.Map<String, Long> increment(String a) { throw new IllegalStateException("redis down"); }
            public java.util.Map<String, Long> current(String a) { return java.util.Map.of(); }
        };
        FraudTopology.build(builder, FraudRulesProperties.defaults(), blocklist, failingVelocity, features);
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-test-fail-" + System.nanoTime());
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        driver = new TopologyTestDriver(builder.build(), p);
        var in = driver.createInputTopic(Topics.TRANSACTIONS, Serdes.String().serializer(), Json.serde(Transaction.class).serializer());
        var out = driver.createOutputTopic(Topics.FRAUD_ALERTS, Serdes.String().deserializer(), Json.serde(FraudAlert.class).deserializer());
        Transaction t = tx("a1", "US", "20000", T0);
        in.pipeInput("a1", t, t.timestamp());
        assertThat(out.readValuesToList()).hasSize(1);
    }

    @Test
    void ruleChangesApplyWithoutRestartingTheTopology() {
        driver.close();
        var current = new java.util.concurrent.atomic.AtomicReference<>(FraudRulesProperties.defaults());
        StreamsBuilder builder = new StreamsBuilder();
        FraudTopology.build(builder, (com.example.fraud.rules.RulesProvider) current::get, blocklist, velocity,
                features);
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-reload-" + System.nanoTime());
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        p.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        driver = new TopologyTestDriver(builder.build(), p);
        input = driver.createInputTopic(Topics.TRANSACTIONS, Serdes.String().serializer(),
                Json.serde(Transaction.class).serializer());
        output = driver.createOutputTopic(Topics.FRAUD_ALERTS, Serdes.String().deserializer(),
                Json.serde(FraudAlert.class).deserializer());

        // 3 txns from 2 locations: below the default threshold of 5 -> no alert, and 15000 is below nothing yet.
        send(tx("r1", "US", "20000.00", T0));
        output.readValuesToList(); // default high-value alert
        send(tx("r1", "GB", "10.00", T0.plusSeconds(1)));
        send(tx("r1", "US", "10.00", T0.plusSeconds(2)));
        assertThat(output.isEmpty()).isTrue();

        // Tighten: >2 txns, high value at 100, 30 second windows.
        current.set(new FraudRulesProperties(Duration.ofSeconds(30), 2, 2, new BigDecimal("100")));
        send(tx("r2", "US", "10.00", T0.plusSeconds(120)));
        send(tx("r2", "GB", "10.00", T0.plusSeconds(121)));
        send(tx("r2", "US", "10.00", T0.plusSeconds(122)));
        List<FraudAlert> alerts = output.readValuesToList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).rule()).isEqualTo(FraudAlert.RULE_VELOCITY_MULTI_LOCATION);
        assertThat(Duration.between(alerts.get(0).windowStart(), alerts.get(0).windowEnd()))
                .isEqualTo(Duration.ofSeconds(30));

        send(tx("r3", "US", "150.00", T0.plusSeconds(200)));
        assertThat(output.readValuesToList()).extracting(FraudAlert::rule).containsExactly(FraudAlert.RULE_HIGH_VALUE);
    }

    @Test
    void alertsCarryRiskScoreFromThePluggableScorer() {
        driver.close();
        StreamsBuilder builder = new StreamsBuilder();
        FraudTopology.build(builder, com.example.fraud.rules.RulesProvider.fixed(FraudRulesProperties.defaults()),
                blocklist, velocity, features, new com.example.fraud.scoring.ZScoreRiskScorer());
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-score-" + System.nanoTime());
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        p.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        driver = new TopologyTestDriver(builder.build(), p);
        input = driver.createInputTopic(Topics.TRANSACTIONS, Serdes.String().serializer(),
                Json.serde(Transaction.class).serializer());
        output = driver.createOutputTopic(Topics.FRAUD_ALERTS, Serdes.String().deserializer(),
                Json.serde(FraudAlert.class).deserializer());

        for (int i = 0; i < 10; i++) send(tx("s1", "US", i % 2 == 0 ? "100.00" : "110.00", T0.plusSeconds(i * 120L)));
        assertThat(output.isEmpty()).isTrue();
        send(tx("s1", "US", "50000.00", T0.plusSeconds(2000)));
        FraudAlert a = output.readValuesToList().get(0);
        assertThat(a.rule()).isEqualTo(FraudAlert.RULE_HIGH_VALUE);
        assertThat(a.riskScore()).isNotNull().isEqualTo(1.0);

        // A new account has no history: alert is raised but without a risk score.
        send(tx("fresh", "US", "50000.00", T0.plusSeconds(2001)));
        assertThat(output.readValuesToList().get(0).riskScore()).isNull();
    }

    @Test
    void failingScorerDoesNotBreakTheStream() {
        driver.close();
        StreamsBuilder builder = new StreamsBuilder();
        FraudTopology.build(builder, com.example.fraud.rules.RulesProvider.fixed(FraudRulesProperties.defaults()),
                blocklist, velocity, features, (t, f) -> { throw new IllegalStateException("boom"); });
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-failscore-" + System.nanoTime());
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        driver = new TopologyTestDriver(builder.build(), p);
        input = driver.createInputTopic(Topics.TRANSACTIONS, Serdes.String().serializer(),
                Json.serde(Transaction.class).serializer());
        output = driver.createOutputTopic(Topics.FRAUD_ALERTS, Serdes.String().deserializer(),
                Json.serde(FraudAlert.class).deserializer());
        send(tx("a1", "US", "50000.00", T0));
        FraudAlert a = output.readValuesToList().get(0);
        assertThat(a.rule()).isEqualTo(FraudAlert.RULE_HIGH_VALUE);
        assertThat(a.riskScore()).isNull();
    }
}
