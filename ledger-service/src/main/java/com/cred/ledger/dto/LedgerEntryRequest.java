package com.cred.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record LedgerEntryRequest(
        @NotNull BigDecimal amount,
        @NotBlank String referenceId,
        @NotBlank String idempotencyKey
) {
    @DecimalMin(value = "0.01", message = "amount must be positive")
    public BigDecimal amount() {
        return amount;
    }
}
