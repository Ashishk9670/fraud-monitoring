package com.example.fraud.cases;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCaseRepository extends JpaRepository<FraudCase, UUID> {
    boolean existsByAlertId(String alertId);

    List<FraudCase> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<FraudCase> findByStatusOrderByCreatedAtDesc(CaseStatus status, Pageable pageable);
}
