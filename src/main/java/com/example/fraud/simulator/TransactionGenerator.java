package com.example.fraud.simulator;

import com.example.fraud.model.Transaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Synthetic transaction generator: normal traffic plus multi-location fraud bursts. */
public class TransactionGenerator {
    static final List<String> LOCATIONS = List.of("US", "GB", "IN", "DE", "BR", "SG", "NG", "RU", "JP", "AU");
    static final List<String> MERCHANTS = List.of("m-grocery", "m-fuel", "m-electronics", "m-travel", "m-coffee", "m-online");

    private final Random random;
    private final int accountPool;

    public TransactionGenerator(long seed, int accountPool) {
        this.random = new Random(seed);
        this.accountPool = accountPool;
    }

    public Transaction normal() {
        int a = random.nextInt(accountPool);
        String home = LOCATIONS.get(a % LOCATIONS.size()); // each account has a stable home location
        return new Transaction(null, "acct-" + a, MERCHANTS.get(random.nextInt(MERCHANTS.size())),
                amount(5, 250), "USD", home, Instant.now());
    }

    /** {@code count} rapid transactions on one account hopping between distinct locations. */
    public List<Transaction> fraudBurst(String accountId, int count) {
        List<Transaction> out = new ArrayList<>();
        Instant base = Instant.now();
        for (int i = 0; i < count; i++) {
            out.add(new Transaction(null, accountId, MERCHANTS.get(random.nextInt(MERCHANTS.size())),
                    amount(20, 900), "USD", LOCATIONS.get(i % LOCATIONS.size()), base.plusMillis(i * 200L)));
        }
        return out;
    }

    public Transaction highValue(String accountId) {
        return new Transaction(null, accountId, "m-electronics", amount(10_000, 25_000), "USD", "US", Instant.now());
    }

    private BigDecimal amount(double min, double max) {
        return BigDecimal.valueOf(min + random.nextDouble() * (max - min)).setScale(2, RoundingMode.HALF_UP);
    }
}
