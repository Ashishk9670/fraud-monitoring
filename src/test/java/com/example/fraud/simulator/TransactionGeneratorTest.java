package com.example.fraud.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fraud.model.Transaction;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TransactionGeneratorTest {
    @Test
    void normalTransactionsAreValidAndDeterministicPerSeed() {
        Transaction a = new TransactionGenerator(1, 10).normal();
        assertThat(a.accountId()).startsWith("acct-");
        assertThat(a.amount()).isPositive();
        assertThat(new TransactionGenerator(1, 10).normal().accountId()).isEqualTo(a.accountId());
    }

    @Test
    void burstHasDistinctLocationsOnOneAccount() {
        List<Transaction> burst = new TransactionGenerator(1, 10).fraudBurst("victim", 7);
        assertThat(burst).hasSize(7);
        assertThat(burst.stream().map(Transaction::accountId).collect(Collectors.toSet())).containsExactly("victim");
        assertThat(burst.stream().map(Transaction::location).distinct().count()).isGreaterThan(1);
    }

    @Test
    void highValueExceedsDefaultThreshold() {
        assertThat(new TransactionGenerator(1, 10).highValue("x").amount()).isGreaterThanOrEqualTo(new java.math.BigDecimal("10000"));
    }
}
