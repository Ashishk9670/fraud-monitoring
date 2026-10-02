package com.example.fraud.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A payment transaction as ingested by the REST API and consumed by the streams topology. */
public record Transaction(
        @Size(max = 64) String id,
        @NotBlank @Size(max = 64) String accountId,
        @NotBlank @Size(max = 64) String merchantId,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax("1000000000") BigDecimal amount,
        @NotBlank @Size(max = 8) String currency,
        @NotBlank @Size(max = 64) String location,
        Instant timestamp) {

    public Transaction {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (timestamp == null) timestamp = Instant.now();
    }
}
