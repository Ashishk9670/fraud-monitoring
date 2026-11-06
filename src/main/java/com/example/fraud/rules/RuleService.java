package com.example.fraud.rules;

import com.example.fraud.config.FraudRulesProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns the runtime rule parameters. The effective value is a volatile snapshot read by the topology for every record;
 * it is refreshed immediately on update and re-read from Postgres on a short poll so every instance converges.
 */
@Service
public class RuleService implements RulesProvider {
    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    private final RuleConfigRepository repo;
    private final FraudRulesProperties defaults;
    private volatile RuleSettings settings;
    private volatile FraudRulesProperties snapshot;

    public RuleService(RuleConfigRepository repo, FraudRulesProperties defaults) {
        this.repo = repo;
        this.defaults = defaults;
        publish(RuleSettings.from(defaults));
    }

    /** Seeds the row from application properties on first start, otherwise loads the stored values. */
    @PostConstruct
    void init() {
        try {
            RuleSettings stored = repo.findById(RuleConfigEntity.SINGLETON_ID)
                    .map(RuleConfigEntity::toSettings)
                    .orElseGet(() -> repo.save(RuleConfigEntity.of(RuleSettings.from(defaults), "bootstrap"))
                            .toSettings());
            publish(stored);
        } catch (RuntimeException e) {
            log.warn("Could not load rules from the database, using configured defaults: {}", e.toString());
        }
    }

    @Override
    public FraudRulesProperties current() {
        return snapshot;
    }

    public RuleSettings settings() {
        return settings;
    }

    @Transactional
    public RuleSettings update(RuleUpdate update, String by) {
        RuleConfigEntity row = repo.findById(RuleConfigEntity.SINGLETON_ID)
                .orElseGet(() -> RuleConfigEntity.of(RuleSettings.from(defaults), "bootstrap"));
        RuleSettings merged = RuleValidator.merge(row.toSettings(), update);
        row.apply(merged, by);
        RuleSettings saved = repo.save(row).toSettings();
        // Publish only once the row is committed: a concurrent reload could otherwise read the previous row and
        // overwrite the new value, and a rolled-back update must never reach the running topology.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(saved);
                }
            });
        } else {
            publish(saved);
        }
        log.info("Fraud rules updated by {}: {}", by, saved);
        return saved;
    }

    @Scheduled(fixedDelayString = "${fraud.rules-reload-ms:2000}", initialDelayString = "${fraud.rules-reload-ms:2000}")
    void reload() {
        try {
            repo.findById(RuleConfigEntity.SINGLETON_ID).map(RuleConfigEntity::toSettings).ifPresent(this::publish);
        } catch (RuntimeException e) {
            log.debug("Rule reload failed, keeping current rules: {}", e.toString());
        }
    }

    /** Monotonic: a snapshot older than the one already published (e.g. a reload that read before an update) is ignored. */
    private synchronized void publish(RuleSettings s) {
        RuleSettings current = this.settings;
        if (current != null && current.updatedAt() != null && s.updatedAt() != null
                && s.updatedAt().isBefore(current.updatedAt())) {
            return;
        }
        this.settings = s;
        this.snapshot = s.toProperties();
    }
}
