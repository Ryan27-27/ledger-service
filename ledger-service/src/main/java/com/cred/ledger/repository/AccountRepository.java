package com.cred.ledger.repository;

import com.cred.ledger.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUserId(String userId);

    /**
     * Pessimistic row lock (SELECT ... FOR UPDATE). Used on the debit/redemption
     * path where contention is expected (e.g. a flash-sale style redemption event)
     * and we'd rather block briefly than burn retries on optimistic-lock failures.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);
}
