package com.cred.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversalRequest(
        @Size(max = 500) String reason,
        @NotBlank @Size(max = 255) String idempotencyKey
) {
}
