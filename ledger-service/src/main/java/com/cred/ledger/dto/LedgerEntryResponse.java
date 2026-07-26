package com.cred.ledger.dto;

import com.cred.ledger.domain.EntryStatus;
import com.cred.ledger.domain.EntryType;
import com.cred.ledger.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID accountId,
        BigDecimal amount,
        EntryType type,
        EntryStatus status,
        String referenceId,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(
                e.getId(), e.getAccountId(), e.getAmount(), e.getType(),
                e.getStatus(), e.getReferenceId(), e.getCreatedAt()
        );
    }
}
