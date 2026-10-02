package com.example.fraud.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A payment transaction as ingested by the REST API and consumed by the streams topology. */
public record Transaction(
        String id,
        @NotBlank String accountId,
        @NotBlank String merchantId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank String currency,
        @NotBlank String location,
        Instant timestamp) {

    public Transaction {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (timestamp == null) timestamp = Instant.now();
    }
}
