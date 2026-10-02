package com.example.fraud.cases;

import com.example.fraud.model.FraudAlert;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseService {
    private final FraudCaseRepository repo;

    public CaseService(FraudCaseRepository repo) {
        this.repo = repo;
    }

    /** Idempotent: a redelivered alert does not create a second case. */
    public Optional<FraudCase> openCase(FraudAlert alert) {
        if (repo.existsByAlertId(alert.id())) return Optional.empty();
        try {
            return Optional.of(repo.save(FraudCase.from(alert)));
        } catch (DataIntegrityViolationException dup) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<FraudCase> list(CaseStatus status, int limit) {
        var page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        return status == null ? repo.findAllByOrderByCreatedAtDesc(page) : repo.findByStatusOrderByCreatedAtDesc(status, page);
    }

    @Transactional(readOnly = true)
    public Optional<FraudCase> find(UUID id) {
        return repo.findById(id);
    }

    @Transactional
    public FraudCase update(UUID id, CaseStatus status, String assignee, String notes) {
        FraudCase c = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Case not found: " + id));
        c.update(status, assignee, notes);
        return c;
    }
}
