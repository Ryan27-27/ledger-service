package com.cred.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record LedgerEntryRequest(
        @NotNull
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 17, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount,

        @NotBlank @Size(max = 255) String referenceId,

        @NotBlank @Size(max = 255) String idempotencyKey
) {
}
