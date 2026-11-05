package com.example.fraud.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.fraud.model.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZScoreRiskScorerTest {
    final ZScoreRiskScorer scorer = new ZScoreRiskScorer();

    static Transaction tx(String amount) {
        return new Transaction(null, "a", "m", new BigDecimal(amount), "USD", "US", Instant.now());
    }

    /** Features as the feature store holds them: history of {@code values} plus the current transaction. */
    static Map<String, String> features(double current, double... values) {
        double sum = current, sq = current * current;
        for (double v : values) {
            sum += v;
            sq += v * v;
        }
        return Map.of("txn_count", String.valueOf(values.length + 1), "total_amount", String.valueOf(sum),
                "total_amount_sq", String.valueOf(sq));
    }

    @Test
    void noScoreWithoutEnoughHistory() {
        assertThat(scorer.score(tx("500"), features(500, 100, 100, 100))).isEmpty();
        assertThat(scorer.score(tx("500"), Map.of())).isEmpty();
        assertThat(scorer.score(tx("500"), Map.of("txn_count", "x", "total_amount", "1", "total_amount_sq", "1")))
                .isEmpty();
    }

    @Test
    void typicalAmountScoresZero() {
        var s = scorer.score(tx("100"), features(100, 90, 110, 100, 95, 105));
        assertThat(s).isPresent();
        assertThat(s.getAsDouble()).isLessThan(0.1);
    }

    @Test
    void outlierScoresHighAndIsCappedAtOne() {
        var s = scorer.score(tx("100000"), features(100000, 90, 110, 100, 95, 105));
        assertThat(s.getAsDouble()).isEqualTo(1.0);
    }

    @Test
    void unusuallySmallAmountIsNotRisky() {
        var s = scorer.score(tx("1"), features(1, 500, 520, 480, 510, 490));
        assertThat(s.getAsDouble()).isZero();
    }

    @Test
    void zScoreMatchesTheDefinition() {
        // history 10,12,14,16,18 -> mean 14, population sd sqrt(8)
        double sum = 70, sq = 100 + 144 + 196 + 256 + 324;
        double z = ZScoreRiskScorer.zScore(20, 5, sum, sq).getAsDouble();
        assertThat(z).isCloseTo((20 - 14) / Math.sqrt(8), within(1e-9));
        assertThat(ZScoreRiskScorer.risk(z)).isCloseTo(z / 6, within(1e-9));
    }

    @Test
    void perfectlyRegularHistoryDoesNotDivideByZero() {
        var s = scorer.score(tx("200"), features(200, 100, 100, 100, 100, 100));
        assertThat(s.getAsDouble()).isEqualTo(1.0);
        assertThat(scorer.score(tx("100"), features(100, 100, 100, 100, 100, 100)).getAsDouble()).isZero();
    }
}
