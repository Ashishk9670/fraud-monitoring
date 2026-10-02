package com.example.fraud.streams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fraud.json.Json;
import com.example.fraud.model.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void transactionRoundTrips() {
        Transaction t = new Transaction("id-1", "a", "m", new BigDecimal("12.34"), "EUR", "DE", Instant.parse("2025-01-01T00:00:00Z"));
        var serde = Json.serde(Transaction.class);
        assertThat(serde.deserializer().deserialize("t", serde.serializer().serialize("t", t))).isEqualTo(t);
    }

    @Test
    void transactionDefaultsIdAndTimestamp() {
        Transaction t = new Transaction(null, "a", "m", BigDecimal.ONE, "USD", "US", null);
        assertThat(t.id()).isNotBlank();
        assertThat(t.timestamp()).isNotNull();
    }

    @Test
    void nullsPassThrough() {
        var serde = Json.serde(Transaction.class);
        assertThat(serde.serializer().serialize("t", null)).isNull();
        assertThat(serde.deserializer().deserialize("t", null)).isNull();
    }

    @Test
    void malformedPayloadThrows() {
        var serde = Json.serde(Transaction.class);
        assertThatThrownBy(() -> serde.deserializer().deserialize("t", "{nope".getBytes()))
                .isInstanceOf(IllegalStateException.class);
    }
}
