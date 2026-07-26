package com.cred.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only ledger row. NEVER updated or deleted after creation
 * (status flips POSTED -> REVERSED via a separate compensating row,
 * see LedgerService#reverse()).
 *
 * idempotencyKey has a unique DB constraint so a retried request
 * (client timeout + retry, network blip, etc.) can never be double
 * counted -- the second insert attempt fails fast at the DB level.
 */
@Entity
@Table(
    name = "ledger_entries",
    uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key", columnNames = "idempotencyKey")
)
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

    /** Client-supplied key that makes the write safe to retry */
    @Column(nullable = false)
    private String idempotencyKey;

    /** Free-text note, e.g. "reversal of entry <id>" */
    private String remarks;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public LedgerEntry(UUID accountId, BigDecimal amount, EntryType type,
                        String referenceId, String idempotencyKey) {
        this.accountId = accountId;
        this.amount = amount;
        this.type = type;
        this.referenceId = referenceId;
        this.idempotencyKey = idempotencyKey;
    }
}
