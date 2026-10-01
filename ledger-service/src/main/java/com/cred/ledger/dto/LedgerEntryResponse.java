package com.cred.ledger.dto;

import com.cred.ledger.domain.EntryStatus;
import com.cred.ledger.domain.EntryType;
import com.cred.ledger.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * @param runningBalance balance of the account immediately after this entry;
 *                       only populated by the history endpoint (null elsewhere)
 * @param reversalOf     for a compensating entry, the id of the entry it reverses
 */
public record LedgerEntryResponse(
        UUID id,
        UUID accountId,
        BigDecimal amount,
        EntryType type,
        EntryStatus status,
        String referenceId,
        String remarks,
        UUID reversalOf,
        Instant createdAt,
        BigDecimal runningBalance
) {
    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(
                e.getId(), e.getAccountId(), e.getAmount(), e.getType(), e.getStatus(),
                e.getReferenceId(), e.getRemarks(), e.getReversalOf(), e.getCreatedAt(), null
        );
    }
}
