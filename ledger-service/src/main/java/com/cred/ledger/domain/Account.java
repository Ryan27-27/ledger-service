package com.cred.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Account holds a DENORMALIZED cached balance for fast reads.
 * The source of truth is always the LedgerEntry table -- this cache is
 * verified against a full replay of the log by LedgerService#audit().
 *
 * @Version enables optimistic locking: two concurrent credits that both
 * read-modify-write the balance can't both win -- one fails with an
 * optimistic-lock exception and is retried. Debits additionally take a
 * pessimistic row lock (see AccountRepository#findByIdForUpdate).
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String userId;

    @Column(nullable = false)
    private BigDecimal cachedBalance = BigDecimal.ZERO;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    public Account(String userId) {
        this.userId = userId;
        this.cachedBalance = BigDecimal.ZERO;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
