package com.cred.ledger.repository;

import com.cred.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Optional<LedgerEntry> findByAccountIdAndIdempotencyKey(UUID accountId, String idempotencyKey);

    Optional<LedgerEntry> findByIdAndAccountId(UUID id, UUID accountId);

    long countByAccountId(UUID accountId);

    /**
     * Recomputes the balance from the full log: SUM(credits) - SUM(debits).
     *
     * Every entry counts, including REVERSED originals: a reversal is modelled
     * as a compensating entry of the opposite type, so the original and its
     * compensation net to zero. (Filtering on status here would drop the
     * original but keep the compensation and double-count the correction.)
     *
     * Returns null when the account has no entries; callers treat that as zero.
     */
    @Query("""
        select sum(case when e.type = com.cred.ledger.domain.EntryType.CREDIT
                        then e.amount else -e.amount end)
        from LedgerEntry e
        where e.accountId = :accountId
        """)
    BigDecimal computeBalance(@Param("accountId") UUID accountId);
}
