package com.example.fraud.scoring;

import com.example.fraud.model.Transaction;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Default heuristic: one-sided z-score of the transaction amount against the account's own history in the feature
 * store ({@code txn_count}, {@code total_amount}, {@code total_amount_sq}), squashed to [0, 1] as {@code z / 6}.
 *
 * <p>The feature snapshot already includes the current transaction, so its contribution is removed first. With fewer
 * than {@link #MIN_HISTORY} prior transactions there is no meaningful baseline and no score is produced.
 */
public class ZScoreRiskScorer implements RiskScorer {
    public static final int MIN_HISTORY = 5;
    static final double Z_AT_MAX_RISK = 6.0;

    @Override
    public OptionalDouble score(Transaction txn, Map<String, String> features) {
        Double count = parse(features, "txn_count");
        Double sum = parse(features, "total_amount");
        Double sumSq = parse(features, "total_amount_sq");
        if (count == null || sum == null || sumSq == null) return OptionalDouble.empty();
        double x = txn.amount().doubleValue();
        OptionalDouble z = zScore(x, count - 1, sum - x, sumSq - x * x);
        return z.isPresent() ? OptionalDouble.of(risk(z.getAsDouble())) : OptionalDouble.empty();
    }

    /** z-score of {@code x} against {@code n} prior observations; empty when history is too short. */
    public static OptionalDouble zScore(double x, double n, double sum, double sumSq) {
        if (n < MIN_HISTORY) return OptionalDouble.empty();
        double mean = sum / n;
        double variance = Math.max(0.0, sumSq / n - mean * mean);
        // Floor the deviation so a perfectly regular history does not make every deviation infinite.
        double sd = Math.max(Math.sqrt(variance), Math.max(0.01 * Math.abs(mean), 0.01));
        return OptionalDouble.of((x - mean) / sd);
    }

    /** Maps a z-score to a risk in [0, 1]; only unusually large amounts are risky. */
    public static double risk(double z) {
        return Math.max(0.0, Math.min(1.0, z / Z_AT_MAX_RISK));
    }

    private static Double parse(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) return null;
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
