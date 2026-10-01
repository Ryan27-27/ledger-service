package com.cred.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only ledger row. Financial facts (account, amount, type, reference,
 * idempotency key, remarks, reversal link, timestamp) are NEVER changed after
 * insert; a mistake is corrected by inserting a compensating entry. The one
 * mutable column is {@code status} (POSTED -> REVERSED), which marks an entry
 * as already corrected. A database trigger (see V3 migration) enforces all of
 * this, so even a buggy code path or manual SQL cannot rewrite history.
 *
 * (account_id, idempotency_key) is unique, so a retried request can never be
 * double counted -- the second insert fails fast at the DB level.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryStatus status = EntryStatus.POSTED;

    /** Business reference, e.g. "bill-payment-12345" or "redemption-98765" */
    @Column(nullable = false)
    private String referenceId;

    /** Client-supplied key that makes the write safe to retry (unique per account) */
    @Column(nullable = false)
    private String idempotencyKey;

    /** Free-text note, e.g. the reason given for a reversal */
    private String remarks;

    /** If this is a compensating entry, the id of the entry it reverses. */
    private UUID reversalOf;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public LedgerEntry(UUID accountId, BigDecimal amount, EntryType type,
                       String referenceId, String idempotencyKey,
                       String remarks, UUID reversalOf) {
        this.accountId = accountId;
        this.amount = amount;
        this.type = type;
        this.referenceId = referenceId;
        this.idempotencyKey = idempotencyKey;
        this.remarks = remarks;
        this.reversalOf = reversalOf;
    }
}
