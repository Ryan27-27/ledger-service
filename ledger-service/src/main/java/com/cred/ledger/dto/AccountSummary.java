package com.cred.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountSummary(UUID accountId, String username, String role, BigDecimal balance, Instant createdAt) {
}
