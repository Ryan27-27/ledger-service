package com.cred.ledger.controller;

import com.cred.ledger.config.RateLimiter;
import com.cred.ledger.domain.LedgerEntry;
import com.cred.ledger.dto.AuditResponse;
import com.cred.ledger.dto.BalanceResponse;
import com.cred.ledger.dto.LedgerEntryRequest;
import com.cred.ledger.dto.LedgerEntryResponse;
import com.cred.ledger.dto.PageResponse;
import com.cred.ledger.dto.ReversalRequest;
import com.cred.ledger.security.AccountAccessPolicy;
import com.cred.ledger.service.LedgerService;
import com.cred.ledger.service.exception.DuplicateRequestException;
import com.cred.ledger.service.exception.RateLimitExceededException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
@Tag(name = "Ledger", description = "Balance, history, credits, debits, reversals and audit for one account")
public class LedgerController {

    private static final int MAX_OPTIMISTIC_ATTEMPTS = 3;
    private static final int MAX_PAGE_SIZE = 100;

    private final LedgerService ledgerService;
    private final RateLimiter debitRateLimiter;
    private final AccountAccessPolicy policy;

    public LedgerController(LedgerService ledgerService,
                            @Qualifier("debitRateLimiter") RateLimiter debitRateLimiter,
                            AccountAccessPolicy policy) {
        this.ledgerService = ledgerService;
        this.debitRateLimiter = debitRateLimiter;
        this.policy = policy;
    }

    @Operation(summary = "Credit points (admin; or owner in demo mode). Idempotent on idempotencyKey.")
    @PostMapping("/credits")
    public ResponseEntity<LedgerEntryResponse> credit(
            @PathVariable UUID accountId,
            @Valid @RequestBody LedgerEntryRequest request) {
        policy.requireCreditOrReverse(accountId);
        return execute(accountId, request.idempotencyKey(), () -> ledgerService.credit(accountId, request));
    }

    @Operation(summary = "Redeem points. Rate limited per account; idempotent on idempotencyKey.")
    @PostMapping("/debits")
    public ResponseEntity<LedgerEntryResponse> debit(
            @PathVariable UUID accountId,
            @Valid @RequestBody LedgerEntryRequest request) {
        policy.requireAccess(accountId);
        if (!debitRateLimiter.tryConsume(accountId.toString())) {
            throw new RateLimitExceededException("Too many redemption attempts, please slow down");
        }
        return execute(accountId, request.idempotencyKey(), () -> ledgerService.debit(accountId, request));
    }

    @Operation(summary = "Reverse an entry by posting a compensating entry (admin; or owner in demo mode)")
    @PostMapping("/entries/{entryId}/reverse")
    public ResponseEntity<LedgerEntryResponse> reverse(
            @PathVariable UUID accountId,
            @PathVariable UUID entryId,
            @Valid @RequestBody ReversalRequest request) {
        policy.requireCreditOrReverse(accountId);
        String reason = request.reason() == null || request.reason().isBlank()
                ? "manual correction" : request.reason().trim();
        return execute(accountId, request.idempotencyKey(),
                () -> ledgerService.reverse(accountId, entryId, reason, request.idempotencyKey()));
    }

    @Operation(summary = "Current (cached) balance")
    @GetMapping("/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable UUID accountId) {
        policy.requireAccess(accountId);
        return ResponseEntity.ok(new BalanceResponse(accountId, ledgerService.getCachedBalance(accountId)));
    }

    @Operation(summary = "Entry history, newest first, with the running balance after each entry")
    @GetMapping("/entries")
    public ResponseEntity<PageResponse<LedgerEntryResponse>> getHistory(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        policy.requireAccess(accountId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ResponseEntity.ok(ledgerService.getHistory(accountId, safePage, safeSize));
    }

    @Operation(summary = "Replay the whole log and compare it with the cached balance")
    @GetMapping("/audit")
    public ResponseEntity<AuditResponse> audit(@PathVariable UUID accountId) {
        policy.requireAccess(accountId);
        return ResponseEntity.ok(ledgerService.audit(accountId));
    }

    /**
     * Runs a write and maps the idempotency outcomes: a fresh write -> 201; a
     * replay of the same request -> 200 with the ORIGINAL entry. If two
     * identical requests race, the loser trips the unique constraint; we then
     * look the winner up and answer as a replay instead of returning a 500.
     */
    private ResponseEntity<LedgerEntryResponse> execute(UUID accountId, String idempotencyKey,
                                                        Supplier<LedgerEntry> operation) {
        try {
            LedgerEntry entry = withOptimisticRetry(operation);
            return ResponseEntity.status(HttpStatus.CREATED).body(LedgerEntryResponse.from(entry));
        } catch (DuplicateRequestException e) {
            return ResponseEntity.ok(LedgerEntryResponse.from(e.getExistingEntry()));
        } catch (DataIntegrityViolationException e) {
            return ledgerService.findByIdempotencyKey(accountId, idempotencyKey)
                    .map(existing -> ResponseEntity.ok(LedgerEntryResponse.from(existing)))
                    .orElseThrow(() -> e);
        }
    }

    /** Credits use optimistic locking, so a lost race is retried in a fresh transaction. */
    private <T> T withOptimisticRetry(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_OPTIMISTIC_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }
}
