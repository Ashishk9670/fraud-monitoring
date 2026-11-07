package com.example.fraud.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fraud.model.Transaction;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks exactly what is sent to ClickHouse, against a real local HTTP server (no Docker). */
class ClickHouseBatchInsertTest {
    record Req(String query, String body) {}

    private HttpServer server;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private ClickHouseAnalyticsStore store;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            requests.add(new Req(q, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        store = new ClickHouseAnalyticsStore(new ClickHouseProperties("http://127.0.0.1:" + server.getAddress().getPort(), "u", "p", "db"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Transaction tx(int i) {
        return new Transaction("t" + i, "acct-" + (i % 3), "m", new BigDecimal("12.50"), "USD", "US", Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
    }

    @Test
    void aBatchIsOneRequestWithOneJsonRowPerLine() {
        List<Transaction> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) batch.add(tx(i));
        store.indexTransactions(batch);

        assertThat(requests).hasSize(1);
        Req r = requests.get(0);
        assertThat(r.query()).isEqualTo("query=INSERT INTO transactions FORMAT JSONEachRow");
        String[] lines = r.body().split("\n");
        assertThat(lines).hasSize(100);
        assertThat(lines[0]).contains("\"id\":\"t0\"").contains("\"account_id\":\"acct-0\"").contains("\"ts\":\"2026-01-01 00:00:00.000\"");
        assertThat(lines[99]).contains("\"id\":\"t99\"");
    }

    @Test
    void anEmptyBatchSendsNothing() {
        store.indexTransactions(List.of());
        assertThat(requests).isEmpty();
    }

    @Test
    void theSingleRecordPathStillWorks() {
        store.indexTransaction(tx(1));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).body().split("\n")).hasSize(1);
    }
}
