package com.example.fraud.streams;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fraud.json.Json;
import com.example.fraud.model.Transaction;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TxnStatsTest {
    private Transaction tx(String loc) {
        return new Transaction(null, "a", "m", new BigDecimal("10.00"), "USD", loc, null);
    }

    @Test
    void firesExactlyOnceWhenThresholdCrossed() {
        TxnStats s = TxnStats.empty();
        int fired = 0;
        for (int i = 0; i < 9; i++) {
            s = s.add(tx(i % 2 == 0 ? "US" : "GB"), 5, 2);
            if (s.justTriggered()) fired++;
        }
        assertThat(fired).isEqualTo(1);
        assertThat(s.triggered()).isTrue();
        assertThat(s.count()).isEqualTo(9);
        assertThat(s.total()).isEqualByComparingTo("90.00");
    }

    @Test
    void firesLateIfSecondLocationArrivesAfterCountExceeded() {
        TxnStats s = TxnStats.empty();
        for (int i = 0; i < 6; i++) s = s.add(tx("US"), 5, 2);
        assertThat(s.triggered()).isFalse();
        s = s.add(tx("GB"), 5, 2);
        assertThat(s.justTriggered()).isTrue();
    }

    @Test
    void survivesJsonRoundTrip() {
        TxnStats s = TxnStats.empty().add(tx("US"), 5, 2).add(tx("GB"), 5, 2);
        var serde = Json.serde(TxnStats.class);
        TxnStats back = serde.deserializer().deserialize("t", serde.serializer().serialize("t", s));
        assertThat(back.count()).isEqualTo(2);
        assertThat(back.locations()).containsExactlyInAnyOrder("US", "GB");
    }
}
