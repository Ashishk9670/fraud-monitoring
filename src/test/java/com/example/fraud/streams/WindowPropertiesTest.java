package com.example.fraud.streams;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fraud.model.Transaction;
import com.example.fraud.rules.RuleSettings;
import com.example.fraud.rules.RuleUpdate;
import com.example.fraud.rules.RuleValidator;
import com.example.fraud.scoring.ZScoreRiskScorer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/** Property-based tests for the window arithmetic, threshold folding and rule validation. */
class WindowPropertiesTest {
    static final String[] LOCATIONS = {"US", "GB", "IN", "DE", "BR", "JP"};

    @Provide
    Arbitrary<Transaction> txns() {
        return Combinators.combine(
                        Arbitraries.of(LOCATIONS),
                        Arbitraries.bigDecimals().between(new BigDecimal("0.01"), new BigDecimal("100000")).ofScale(2))
                .as((loc, amount) -> new Transaction("t", "acct", "m", amount, "USD", loc, Instant.EPOCH));
    }

    // ---- window arithmetic -------------------------------------------------------------------------------------

    @Property
    void timestampFallsInsideItsWindow(@ForAll @LongRange(min = -10_000_000_000L, max = 10_000_000_000_000L) long ts,
                                       @ForAll @LongRange(min = 1, max = 86_400_000L) long size) {
        long start = WindowMath.windowStart(ts, size);
        assertThat(start).isLessThanOrEqualTo(ts);
        assertThat(ts).isLessThan(start + size);
        assertThat(Math.floorMod(start, size)).isZero();
    }

    @Property
    void windowStartIsIdempotentAndSharedWithinAWindow(
            @ForAll @LongRange(min = 0, max = 10_000_000_000_000L) long ts,
            @ForAll @LongRange(min = 1, max = 86_400_000L) long size,
            @ForAll @LongRange(min = 0, max = 86_399_999L) long offset) {
        long start = WindowMath.windowStart(ts, size);
        assertThat(WindowMath.windowStart(start, size)).isEqualTo(start);
        long sameWindow = start + (offset % size);
        assertThat(WindowMath.windowStart(sameWindow, size)).isEqualTo(start);
        assertThat(WindowMath.windowStart(start + size, size)).isEqualTo(start + size);
    }

    // ---- threshold folding (TxnStats) --------------------------------------------------------------------------

    @Property
    void alertFiresAtMostOnceAndExactlyWhenBothThresholdsAreMet(
            @ForAll @Size(max = 40) List<@From("txns") Transaction> txns,
            @ForAll @IntRange(min = 1, max = 15) int maxTxns,
            @ForAll @IntRange(min = 1, max = 6) int minLocations) {
        TxnStats s = TxnStats.empty();
        Set<String> seen = new HashSet<>();
        int fired = 0;
        boolean shouldHaveFiredByNow = false;
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < txns.size(); i++) {
            Transaction t = txns.get(i);
            seen.add(t.location());
            total = total.add(t.amount());
            shouldHaveFiredByNow |= (i + 1) > maxTxns && seen.size() >= minLocations;
            s = s.add(t, maxTxns, minLocations);
            if (s.justTriggered()) fired++;
            assertThat(s.count()).isEqualTo(i + 1);
            assertThat(s.locations()).isEqualTo(seen);
            assertThat(s.triggered()).isEqualTo(shouldHaveFiredByNow);
        }
        assertThat(s.total()).isEqualByComparingTo(total);
        assertThat(fired).isEqualTo(shouldHaveFiredByNow ? 1 : 0);
    }

    @Property
    void neverFiresWithFewerThanThresholdTransactions(
            @ForAll @Size(max = 10) List<@From("txns") Transaction> txns, @ForAll @IntRange(min = 10, max = 20) int max) {
        TxnStats s = TxnStats.empty();
        for (Transaction t : txns) s = s.add(t, max, 1);
        assertThat(s.triggered()).isFalse();
    }

    // ---- rule validation ---------------------------------------------------------------------------------------

    static final RuleSettings BASE = new RuleSettings(60, 5, 2, new BigDecimal("10000"), null, null);

    @Property
    void anyInBoundsUpdateIsAcceptedAndAppliedVerbatim(
            @ForAll @IntRange(min = 1, max = RuleValidator.MAX_WINDOW_SECONDS) int window,
            @ForAll @IntRange(min = 1, max = RuleValidator.MAX_TRANSACTIONS) int max,
            @ForAll @IntRange(min = 1, max = RuleValidator.MAX_LOCATIONS) int locs,
            @ForAll @DoubleRange(min = 0.01, max = 1_000_000_000) double amount) {
        BigDecimal a = BigDecimal.valueOf(amount);
        RuleSettings s = RuleValidator.merge(BASE, new RuleUpdate(window, max, locs, a));
        assertThat(s.windowSeconds()).isEqualTo(window);
        assertThat(s.maxTransactions()).isEqualTo(max);
        assertThat(s.minDistinctLocations()).isEqualTo(locs);
        assertThat(s.highValueAmount()).isEqualByComparingTo(a);
        assertThat(s.toProperties().window().toSeconds()).isEqualTo(window);
        // merging the same update again changes nothing
        assertThat(RuleValidator.merge(s, new RuleUpdate(window, max, locs, a))).isEqualTo(s);
    }

    @Property
    void outOfBoundsWindowIsAlwaysRejected(@ForAll("badWindows") int window) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> RuleValidator.merge(BASE, new RuleUpdate(window, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Provide
    Arbitrary<Integer> badWindows() {
        return Arbitraries.oneOf(Arbitraries.integers().lessOrEqual(0),
                Arbitraries.integers().greaterOrEqual(RuleValidator.MAX_WINDOW_SECONDS + 1));
    }

    @Property
    void emptyUpdateKeepsEverything() {
        assertThat(RuleValidator.merge(BASE, new RuleUpdate(null, null, null, null))).isEqualTo(BASE);
    }

    // ---- risk scoring ------------------------------------------------------------------------------------------

    @Property
    void riskIsBoundedAndMonotonicInTheZScore(@ForAll @DoubleRange(min = -1e6, max = 1e6) double z1,
                                               @ForAll @DoubleRange(min = -1e6, max = 1e6) double z2) {
        double r1 = ZScoreRiskScorer.risk(z1);
        double r2 = ZScoreRiskScorer.risk(z2);
        assertThat(r1).isBetween(0.0, 1.0);
        if (z1 <= z2) assertThat(r1).isLessThanOrEqualTo(r2);
    }
}
