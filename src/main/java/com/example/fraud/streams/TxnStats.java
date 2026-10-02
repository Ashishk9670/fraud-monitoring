package com.example.fraud.streams;

import com.example.fraud.model.Transaction;
import java.math.BigDecimal;
import java.util.Set;
import java.util.TreeSet;

/** Per-account, per-window aggregate kept in the Kafka Streams state store. */
public record TxnStats(int count, Set<String> locations, BigDecimal total, boolean triggered, boolean justTriggered) {

    public static TxnStats empty() {
        return new TxnStats(0, Set.of(), BigDecimal.ZERO, false, false);
    }

    /** Folds a transaction in; {@code justTriggered} is true only for the update that first crosses the threshold. */
    public TxnStats add(Transaction t, int maxTransactions, int minDistinctLocations) {
        Set<String> locs = new TreeSet<>(locations);
        locs.add(t.location());
        int c = count + 1;
        boolean fire = !triggered && c > maxTransactions && locs.size() >= minDistinctLocations;
        return new TxnStats(c, locs, total.add(t.amount()), triggered || fire, fire);
    }
}
