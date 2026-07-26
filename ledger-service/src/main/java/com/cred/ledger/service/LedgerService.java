package com.cred.ledger.service;

import com.cred.ledger.domain.Account;
import com.cred.ledger.domain.EntryStatus;
import com.cred.ledger.domain.EntryType;
import com.cred.ledger.domain.LedgerEntry;
import com.cred.ledger.dto.AuditResponse;
import com.cred.ledger.dto.LedgerEntryRequest;
import com.cred.ledger.repository.AccountRepository;
import com.cred.ledger.repository.LedgerEntryRepository;
import com.cred.ledger.service.exception.AccountNotFoundException;
import com.cred.ledger.service.exception.DuplicateRequestException;
import com.cred.ledger.service.exception.InsufficientBalanceException;
import com.cred.ledger.service.exception.InvalidReversalException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public Account createAccount(String userId) {
        return accountRepository.save(new Account(userId));
    }

    /**
     * CREDIT path (points earned). Lower contention than debit in practice
     * (credits come from many independent triggers - bill payments across
     * users), so optimistic locking via @Version is enough: if two credits
     * race on the same account, one retries at the caller/client level.
     */
    @Transactional
    public LedgerEntry credit(UUID accountId, LedgerEntryRequest request) {
        checkIdempotency(request.idempotencyKey());

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        LedgerEntry entry = new LedgerEntry(
                accountId, request.amount(), EntryType.CREDIT,
                request.referenceId(), request.idempotencyKey()
        );
        ledgerEntryRepository.save(entry);

        account.setCachedBalance(account.getCachedBalance().add(request.amount()));
        accountRepository.save(account); // optimistic lock check happens here via @Version

        return entry;
    }

    /**
     * DEBIT path (redemption). This is the contended path -- a user could fire
     * two redemption taps, or a client could retry on timeout while the first
     * request is still in flight. We take a pessimistic row lock on the account
     * so concurrent debits against the SAME account serialize at the DB level,
     * making double-spend structurally impossible rather than just unlikely.
     */
    @Transactional
    public LedgerEntry debit(UUID accountId, LedgerEntryRequest request) {
        checkIdempotency(request.idempotencyKey());

        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        if (account.getCachedBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientBalanceException(
                    "Insufficient balance: have " + account.getCachedBalance() + ", need " + request.amount());
        }

        LedgerEntry entry = new LedgerEntry(
                accountId, request.amount(), EntryType.DEBIT,
                request.referenceId(), request.idempotencyKey()
        );
        ledgerEntryRepository.save(entry);

        account.setCachedBalance(account.getCachedBalance().subtract(request.amount()));
        accountRepository.save(account);

        return entry;
    }

    /**
     * If this idempotencyKey was already used, short-circuit by returning the
     * original entry instead of creating a new one. This is what makes retries
     * (client timeout, network blip, at-least-once delivery from a queue) safe.
     */
    private void checkIdempotency(String idempotencyKey) {
        ledgerEntryRepository.findByIdempotencyKey(idempotencyKey)
                .ifPresent(existing -> {
                    throw new DuplicateRequestException(existing);
                });
    }

    @Transactional(readOnly = true)
    public BigDecimal getCachedBalance(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId))
                .getCachedBalance();
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> getHistory(UUID accountId) {
        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtAsc(accountId);
    }

    /**
     * Ops-initiated correction. Never mutates the original entry (append-only
     * invariant) -- instead posts a compensating entry of the opposite type
     * and amount, and flips the original's status to REVERSED so it's excluded
     * from future reversal attempts but still visible in history for audit.
     *
     * Reuses the credit/debit locking strategy: reversing a DEBIT posts a
     * CREDIT (low contention, optimistic lock via account save); reversing a
     * CREDIT posts a DEBIT and must re-check sufficient balance (pessimistic
     * lock), since the points may have already been spent elsewhere.
     */
    @Transactional
    public LedgerEntry reverse(UUID accountId, UUID entryId, String reason, String idempotencyKey) {
        checkIdempotency(idempotencyKey);

        LedgerEntry original = ledgerEntryRepository.findByIdAndAccountId(entryId, accountId)
                .orElseThrow(() -> new InvalidReversalException("Entry not found on this account: " + entryId));

        if (original.getStatus() == EntryStatus.REVERSED) {
            throw new InvalidReversalException("Entry already reversed: " + entryId);
        }

        EntryType compensatingType = original.getType() == EntryType.CREDIT ? EntryType.DEBIT : EntryType.CREDIT;
        LedgerEntryRequest compensatingRequest = new LedgerEntryRequest(
                original.getAmount(),
                "reversal-of-" + original.getId(),
                idempotencyKey
        );

        LedgerEntry compensatingEntry = compensatingType == EntryType.CREDIT
                ? credit(accountId, compensatingRequest)
                : debit(accountId, compensatingRequest);
        compensatingEntry.setRemarks(reason);
        ledgerEntryRepository.save(compensatingEntry);

        original.setStatus(EntryStatus.REVERSED);
        ledgerEntryRepository.save(original);

        return compensatingEntry;
    }

    /**
     * Replays every POSTED entry for the account and compares the recomputed
     * total against Account.cachedBalance. This is the correctness proof:
     * cachedBalance is just a performance cache, computedBalance from the
     * append-only log is the ground truth.
     */
    @Transactional(readOnly = true)
    public AuditResponse audit(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        BigDecimal computed = ledgerEntryRepository.computeBalance(accountId);
        boolean consistent = account.getCachedBalance().compareTo(computed) == 0;

        return new AuditResponse(accountId, account.getCachedBalance(), computed, consistent);
    }
}
