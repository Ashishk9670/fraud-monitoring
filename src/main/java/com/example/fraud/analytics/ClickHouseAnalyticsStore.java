package com.example.fraud.analytics;

import com.example.fraud.json.Json;
import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Severity;
import com.example.fraud.model.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** ClickHouse via its HTTP interface (no JDBC driver needed). */
@Component
@ConditionalOnProperty(name = "analytics.store", havingValue = "clickhouse", matchIfMissing = true)
public class ClickHouseAnalyticsStore implements AnalyticsStore {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseAnalyticsStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final RestClient http;

    public ClickHouseAnalyticsStore(ClickHouseProperties p) {
        this.http = RestClient.builder().baseUrl(p.url())
                .defaultHeader("X-ClickHouse-User", p.user())
                .defaultHeader("X-ClickHouse-Key", p.password())
                .defaultHeader("X-ClickHouse-Database", p.database())
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initSchema() {
        try {
            createSchema();
        } catch (RuntimeException e) {
            log.warn("ClickHouse schema init failed (will retry on first write): {}", e.toString());
        }
    }

    public void createSchema() {
        exec("CREATE TABLE IF NOT EXISTS transactions (id String, account_id String, merchant_id String, "
                + "amount Float64, currency String, location String, ts DateTime64(3, 'UTC')) "
                + "ENGINE = MergeTree ORDER BY (account_id, ts)");
        exec("CREATE TABLE IF NOT EXISTS alerts (id String, account_id String, transaction_id String, rule String, "
                + "severity String, score Float64, reason String, txn_count Int32, locations Array(String), "
                + "created_at DateTime64(3, 'UTC')) ENGINE = MergeTree ORDER BY created_at");
        exec("ALTER TABLE alerts ADD COLUMN IF NOT EXISTS risk_score Nullable(Float64)");
    }

    private void exec(String sql) {
        http.post().uri("/").contentType(MediaType.TEXT_PLAIN).body(sql).retrieve().toBodilessEntity();
    }

    private void insert(String table, ObjectNode row) {
        String sql = "INSERT INTO " + table + " FORMAT JSONEachRow";
        try {
            http.post().uri(uri -> uri.path("/").queryParam("query", sql).build())
                    .contentType(MediaType.TEXT_PLAIN).body(row.toString()).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            createSchema(); // tables may not exist yet; retry once
            http.post().uri(uri -> uri.path("/").queryParam("query", sql).build())
                    .contentType(MediaType.TEXT_PLAIN).body(row.toString()).retrieve().toBodilessEntity();
        }
    }

    private JsonNode query(String sql) {
        String body = http.post().uri("/").contentType(MediaType.TEXT_PLAIN)
                .body(sql + " FORMAT JSON").retrieve().body(String.class);
        try {
            return Json.MAPPER.readTree(body).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("Bad ClickHouse response", e);
        }
    }

    @Override
    public void indexTransaction(Transaction t) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("id", t.id()).put("account_id", t.accountId()).put("merchant_id", t.merchantId())
                .put("amount", t.amount().doubleValue()).put("currency", t.currency())
                .put("location", t.location()).put("ts", TS.format(t.timestamp()));
        insert("transactions", n);
    }

    @Override
    public List<Transaction> transactionsBetween(Instant from, Instant to, int limit) {
        List<Transaction> out = new ArrayList<>();
        for (JsonNode r : query("SELECT id, account_id, merchant_id, amount, currency, location, "
                + "toUnixTimestamp64Milli(ts) AS ts_ms FROM transactions WHERE ts >= fromUnixTimestamp64Milli("
                + from.toEpochMilli() + ") AND ts < fromUnixTimestamp64Milli(" + to.toEpochMilli() + ") "
                + "ORDER BY ts ASC LIMIT " + Math.max(1, Math.min(limit, 1_000_000)))) {
            out.add(new Transaction(r.path("id").asText(), r.path("account_id").asText(),
                    r.path("merchant_id").asText(), java.math.BigDecimal.valueOf(r.path("amount").asDouble()),
                    r.path("currency").asText(), r.path("location").asText(),
                    Instant.ofEpochMilli(r.path("ts_ms").asLong())));
        }
        return out;
    }

    @Override
    public void indexAlert(FraudAlert a) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("id", a.id()).put("account_id", a.accountId()).put("transaction_id", a.transactionId() == null ? "" : a.transactionId())
                .put("rule", a.rule()).put("severity", a.severity().name()).put("score", a.score())
                .put("reason", a.reason()).put("txn_count", a.transactionCount())
                .put("created_at", TS.format(a.createdAt()));
        if (a.riskScore() == null) n.putNull("risk_score"); else n.put("risk_score", a.riskScore());
        var arr = n.putArray("locations");
        a.locations().forEach(arr::add);
        insert("alerts", n);
    }

    @Override
    public List<FraudAlert> recentAlerts(int limit) {
        List<FraudAlert> out = new ArrayList<>();
        for (JsonNode r : query("SELECT id, account_id, transaction_id, rule, severity, score, reason, txn_count, "
                + "locations, risk_score, toUnixTimestamp64Milli(created_at) AS created_ms FROM alerts "
                + "ORDER BY created_at DESC LIMIT " + Math.max(1, Math.min(limit, 1000)))) {
            List<String> locs = new ArrayList<>();
            r.path("locations").forEach(l -> locs.add(l.asText()));
            Instant created = Instant.ofEpochMilli(r.path("created_ms").asLong());
            String txn = r.path("transaction_id").asText();
            out.add(new FraudAlert(r.path("id").asText(), r.path("account_id").asText(), txn.isEmpty() ? null : txn,
                    r.path("rule").asText(), Severity.valueOf(r.path("severity").asText()),
                    r.path("score").asDouble(), r.path("reason").asText(), r.path("txn_count").asInt(),
                    locs, created, created, created,
                    r.path("risk_score").isNumber() ? r.path("risk_score").asDouble() : null));
        }
        return out;
    }

    @Override
    public List<RuleCount> alertCountsByRule(Duration since) {
        List<RuleCount> out = new ArrayList<>();
        for (JsonNode r : query("SELECT rule, count() AS c FROM alerts WHERE created_at >= now64(3) - INTERVAL "
                + since.toSeconds() + " SECOND GROUP BY rule ORDER BY c DESC")) {
            out.add(new RuleCount(r.path("rule").asText(), r.path("c").asInt()));
        }
        return out;
    }

    @Override
    public List<AccountRisk> topRiskAccounts(Duration since, int limit) {
        List<AccountRisk> out = new ArrayList<>();
        for (JsonNode r : query("SELECT account_id, count() AS c, max(score) AS m FROM alerts "
                + "WHERE created_at >= now64(3) - INTERVAL " + since.toSeconds() + " SECOND "
                + "GROUP BY account_id ORDER BY c DESC, m DESC LIMIT " + Math.max(1, Math.min(limit, 1000)))) {
            out.add(new AccountRisk(r.path("account_id").asText(), r.path("c").asInt(), r.path("m").asDouble()));
        }
        return out;
    }
}
