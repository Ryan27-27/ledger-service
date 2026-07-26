package com.cred.ledger.repository;

import com.cred.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    Optional<LedgerEntry> findByIdAndAccountId(UUID id, UUID accountId);

    /**
     * Recomputes balance directly from POSTED entries: SUM(credits) - SUM(debits).
     * This is the source of truth used by the /audit endpoint to validate
     * (and if needed, correct) Account.cachedBalance.
     */
    @Query("""
        select coalesce(sum(case when e.type = 'CREDIT' then e.amount else -e.amount end), 0)
        from LedgerEntry e
        where e.accountId = :accountId and e.status = 'POSTED'
        """)
    BigDecimal computeBalance(@Param("accountId") UUID accountId);
}
