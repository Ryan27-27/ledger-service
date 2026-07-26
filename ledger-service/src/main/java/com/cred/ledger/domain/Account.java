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
 * The source of truth is always the LedgerEntry table -- this cache
 * is validated/rebuilt by LedgerService#reconcile().
 *
 * @Version enables optimistic locking so two concurrent redemptions
 * against the same account can't both read-modify-write the balance
 * without one of them failing with OptimisticLockException.
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
