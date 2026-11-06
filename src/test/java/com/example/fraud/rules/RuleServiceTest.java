package com.example.fraud.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.fraud.config.FraudRulesProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuleServiceTest {
    RuleConfigRepository repo;
    RuleService service;
    RuleConfigEntity stored;

    @BeforeEach
    void setUp() {
        repo = mock(RuleConfigRepository.class);
        stored = null;
        when(repo.findById(RuleConfigEntity.SINGLETON_ID)).thenAnswer(i -> Optional.ofNullable(stored));
        when(repo.save(any(RuleConfigEntity.class))).thenAnswer(i -> {
            stored = i.getArgument(0);
            return stored;
        });
        service = new RuleService(repo, FraudRulesProperties.defaults());
        service.init();
    }

    @Test
    void seedsDefaultsOnFirstStart() {
        assertThat(stored).isNotNull();
        assertThat(service.current()).isEqualTo(FraudRulesProperties.defaults());
        assertThat(service.settings().updatedBy()).isEqualTo("bootstrap");
    }

    @Test
    void updatePersistsAndPublishesImmediately() {
        service.update(new RuleUpdate(30, 2, null, null), "alice");
        assertThat(service.current().window().toSeconds()).isEqualTo(30);
        assertThat(service.current().maxTransactions()).isEqualTo(2);
        assertThat(service.settings().updatedBy()).isEqualTo("alice");
        assertThat(stored.toSettings().windowSeconds()).isEqualTo(30);
    }

    @Test
    void invalidUpdateIsRejectedAndNothingChanges() {
        assertThatThrownBy(() -> service.update(new RuleUpdate(-1, null, null, null), "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.current()).isEqualTo(FraudRulesProperties.defaults());
    }

    @Test
    void reloadPicksUpChangesMadeByAnotherInstance() {
        stored.apply(new RuleSettings(10, 1, 1, new java.math.BigDecimal("5"), null, null), "other");
        service.reload();
        assertThat(service.current().maxTransactions()).isEqualTo(1);
        assertThat(service.current().window().toSeconds()).isEqualTo(10);
    }

    @Test
    void databaseFailureKeepsCurrentRules() {
        when(repo.findById(RuleConfigEntity.SINGLETON_ID)).thenThrow(new RuntimeException("db down"));
        service.reload();
        assertThat(service.current()).isEqualTo(FraudRulesProperties.defaults());
        verify(repo, never()).deleteById(any());
    }

    @Test
    void staleReloadDoesNotOverwriteANewerUpdate() {
        // A reload read the row before the update committed ...
        RuleSettings stale = stored.toSettings();
        service.update(new RuleUpdate(null, null, 3, null), "alice");
        // ... and publishes afterwards: it must not win
        stored.apply(stale, "stale-reader");
        java.time.Instant older = stale.updatedAt();
        RuleConfigEntity row = stored;
        // reload sees an old updatedAt (restore it) and therefore must be ignored
        try {
            var f = RuleConfigEntity.class.getDeclaredField("updatedAt");
            f.setAccessible(true);
            f.set(row, older);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        service.reload();
        assertThat(service.current().minDistinctLocations()).isEqualTo(3);
        assertThat(service.settings().updatedBy()).isEqualTo("alice");
    }

    @Test
    void updateIsPublishedOnlyAfterCommit() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(new RuleUpdate(null, null, 4, null), "alice");
            assertThat(service.current().minDistinctLocations()).as("not visible before commit")
                    .isEqualTo(FraudRulesProperties.defaults().minDistinctLocations());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            assertThat(service.current().minDistinctLocations()).isEqualTo(4);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolledBackUpdateNeverReachesTheTopology() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(new RuleUpdate(null, null, 4, null), "alice");
            // rollback: afterCommit is never invoked
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(service.current()).isEqualTo(FraudRulesProperties.defaults());
    }
}
