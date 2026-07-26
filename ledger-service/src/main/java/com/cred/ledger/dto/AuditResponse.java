package com.cred.ledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AuditResponse(
        UUID accountId,
        BigDecimal cachedBalance,
        BigDecimal computedBalance,
        boolean consistent
) {
}
