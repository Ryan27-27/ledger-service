package com.cred.ledger.service;

import com.cred.ledger.domain.Account;
import com.cred.ledger.domain.EntryStatus;
import com.cred.ledger.domain.EntryType;
import com.cred.ledger.domain.LedgerEntry;
import com.cred.ledger.dto.AuditResponse;
import com.cred.ledger.dto.LedgerEntryRequest;
import com.cred.ledger.dto.LedgerEntryResponse;
import com.cred.ledger.dto.PageResponse;
import com.cred.ledger.repository.AccountRepository;
import com.cred.ledger.repository.LedgerEntryRepository;
import com.cred.ledger.repository.LedgerQueryRepository;
import com.cred.ledger.service.exception.AccountNotFoundException;
import com.cred.ledger.service.exception.DuplicateRequestException;
import com.cred.ledger.service.exception.IdempotencyKeyReuseException;
import com.cred.ledger.service.exception.InsufficientBalanceException;
import com.cred.ledger.service.exception.InvalidReversalException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerQueryRepository queryRepository;
    private final MeterRegistry meterRegistry;

    public LedgerService(AccountRepository accountRepository,
                         LedgerEntryRepository ledgerEntryRepository,
                         LedgerQueryRepository queryRepository,
                         MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.queryRepository = queryRepository;
        this.meterRegistry = meterRegistry;
    }

    public Account createAccount(String userId) {
        return accountRepository.save(new Account(userId));
    }

    /**
     * CREDIT path (points earned). Lower contention than debit in practice
     * (credits come from many independent triggers), so optimistic locking via
     * @Version is enough: if two credits race on the same account, one fails
     * with an optimistic-lock exception and the controller retries it.
     */
    @Transactional
    public LedgerEntry credit(UUID accountId, LedgerEntryRequest request) {
        BigDecimal amount = normalize(request.amount());
        checkIdempotency(accountId, request.idempotencyKey(), EntryType.CREDIT, amount);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        return post(account, EntryType.CREDIT, amount, request.referenceId(),
                request.idempotencyKey(), null, null);
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
        BigDecimal amount = normalize(request.amount());
        checkIdempotency(accountId, request.idempotencyKey(), EntryType.DEBIT, amount);

        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        return post(account, EntryType.DEBIT, amount, request.referenceId(),
                request.idempotencyKey(), null, null);
    }

    /**
     * Ops-initiated correction. Never rewrites the original entry's financial
     * data (append-only invariant): posts a compensating entry of the opposite
     * type and amount that links back to the original via reversalOf, and flips
     * the original's status to REVERSED so it cannot be reversed again.
     *
     * The account row is locked first, for both directions, so concurrent
     * reversals/debits on the same account serialize. Reversing a CREDIT posts
     * a DEBIT and therefore re-checks the balance -- the points may already
     * have been spent. A reversal entry itself cannot be reversed.
     */
    @Transactional
    public LedgerEntry reverse(UUID accountId, UUID entryId, String reason, String idempotencyKey) {
        Optional<LedgerEntry> replay = ledgerEntryRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
        if (replay.isPresent()) {
            LedgerEntry existing = replay.get();
            if (!entryId.equals(existing.getReversalOf())) {
                throw new IdempotencyKeyReuseException(idempotencyKey);
            }
            throw new DuplicateRequestException(existing);
        }

        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        LedgerEntry original = ledgerEntryRepository.findByIdAndAccountId(entryId, accountId)
                .orElseThrow(() -> new InvalidReversalException("Entry not found on this account: " + entryId));

        if (original.getReversalOf() != null) {
            throw new InvalidReversalException("A reversal entry cannot itself be reversed: " + entryId);
        }
        if (original.getStatus() == EntryStatus.REVERSED) {
            throw new InvalidReversalException("Entry already reversed: " + entryId);
        }

        EntryType compensatingType = original.getType() == EntryType.CREDIT ? EntryType.DEBIT : EntryType.CREDIT;
        LedgerEntry compensating = post(account, compensatingType, original.getAmount(),
                "reversal-of-" + original.getId(), idempotencyKey, reason, original.getId());

        original.setStatus(EntryStatus.REVERSED);
        ledgerEntryRepository.save(original);

        return compensating;
    }

    @Transactional(readOnly = true)
    public BigDecimal getCachedBalance(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId))
                .getCachedBalance();
    }

    /** Newest-first page of history; each row carries the account balance right after it. */
    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> getHistory(UUID accountId, int page, int size) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException("Account not found: " + accountId);
        }
        long total = ledgerEntryRepository.countByAccountId(accountId);
        List<LedgerEntryResponse> items =
                queryRepository.findPageWithRunningBalance(accountId, size, (long) page * size);
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public Optional<LedgerEntry> findByIdempotencyKey(UUID accountId, String idempotencyKey) {
        return ledgerEntryRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
    }

    /**
     * Replays every entry for the account and compares the recomputed total
     * against Account.cachedBalance. cachedBalance is only a performance cache;
     * the append-only log is the ground truth.
     */
    @Transactional(readOnly = true)
    public AuditResponse audit(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        BigDecimal computed = Optional.ofNullable(ledgerEntryRepository.computeBalance(accountId))
                .orElse(BigDecimal.ZERO);
        boolean consistent = account.getCachedBalance().compareTo(computed) == 0;

        return new AuditResponse(accountId, account.getCachedBalance(), computed, consistent);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Applies one entry to an already-loaded (and, for debits/reversals,
     * already-locked) account: balance check, insert, cache update. Flushing
     * eagerly surfaces constraint / version conflicts inside this method so the
     * transaction rolls back cleanly and the caller sees the real cause.
     */
    private LedgerEntry post(Account account, EntryType type, BigDecimal amount,
                             String referenceId, String idempotencyKey,
                             String remarks, UUID reversalOf) {
        BigDecimal balance = account.getCachedBalance();
        if (type == EntryType.DEBIT) {
            if (balance.compareTo(amount) < 0) {
                throw new InsufficientBalanceException(
                        "Insufficient balance: have " + balance + ", need " + amount);
            }
            account.setCachedBalance(balance.subtract(amount));
        } else {
            account.setCachedBalance(balance.add(amount));
        }

        LedgerEntry entry = new LedgerEntry(account.getId(), amount, type, referenceId,
                idempotencyKey, remarks, reversalOf);
        ledgerEntryRepository.saveAndFlush(entry);
        accountRepository.saveAndFlush(account);

        recordAfterCommit(type);
        return entry;
    }

    /**
     * If this idempotency key was already used on this account: identical
     * request -> signal a replay (caller returns the original result);
     * different request -> reject, the key is being misused.
     */
    private void checkIdempotency(UUID accountId, String key, EntryType type, BigDecimal amount) {
        ledgerEntryRepository.findByAccountIdAndIdempotencyKey(accountId, key).ifPresent(existing -> {
            if (existing.getType() != type || existing.getAmount().compareTo(amount) != 0) {
                throw new IdempotencyKeyReuseException(key);
            }
            throw new DuplicateRequestException(existing);
        });
    }

    private static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Count only entries that actually committed. */
    private void recordAfterCommit(EntryType type) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meterRegistry.counter("ledger.entries.posted", "type", type.name()).increment();
            }
        });
    }
}
