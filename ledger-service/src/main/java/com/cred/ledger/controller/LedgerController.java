package com.cred.ledger.controller;

import com.cred.ledger.config.RateLimiter;
import com.cred.ledger.domain.LedgerEntry;
import com.cred.ledger.dto.*;
import com.cred.ledger.security.CurrentUser;
import com.cred.ledger.service.LedgerService;
import com.cred.ledger.service.exception.AccessDeniedException;
import com.cred.ledger.service.exception.DuplicateRequestException;
import com.cred.ledger.service.exception.RateLimitExceededException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class LedgerController {

    private final LedgerService ledgerService;
    private final RateLimiter debitRateLimiter;
    private final CurrentUser currentUser;

    public LedgerController(LedgerService ledgerService, RateLimiter debitRateLimiter, CurrentUser currentUser) {
        this.ledgerService = ledgerService;
        this.debitRateLimiter = debitRateLimiter;
        this.currentUser = currentUser;
    }

    /**
     * A valid JWT proves *who* you are, not that you're allowed to touch
     * *this* account. This closes that gap: the accountId in the token
     * (set at login, sourced from the DB) must match the accountId in the
     * URL, or an ADMIN role is required. Without this check, any logged-in
     * user could read or drain any other user's account just by changing
     * the path.
     */
    private void verifyOwnership(UUID accountId) {
        var user = currentUser.get();
        boolean isOwner = user.accountId().equals(accountId);
        boolean isAdmin = "ADMIN".equals(user.role());
        if (!isOwner && !isAdmin) {
            throw new AccessDeniedException("You do not have access to this account");
        }
    }

    @PostMapping("/credits")
    public ResponseEntity<LedgerEntryResponse> credit(
            @PathVariable UUID accountId,
            @Valid @RequestBody LedgerEntryRequest request) {
        verifyOwnership(accountId);
        try {
            LedgerEntry entry = ledgerService.credit(accountId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(LedgerEntryResponse.from(entry));
        } catch (DuplicateRequestException e) {
            // Idempotent replay: return the original result with 200, not an error.
            return ResponseEntity.ok(LedgerEntryResponse.from(e.getExistingEntry()));
        }
    }

    @PostMapping("/debits")
    public ResponseEntity<LedgerEntryResponse> debit(
            @PathVariable UUID accountId,
            @Valid @RequestBody LedgerEntryRequest request) {
        verifyOwnership(accountId);
        if (!debitRateLimiter.tryConsume(accountId.toString())) {
            throw new RateLimitExceededException("Too many redemption attempts, please slow down");
        }
        try {
            LedgerEntry entry = ledgerService.debit(accountId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(LedgerEntryResponse.from(entry));
        } catch (DuplicateRequestException e) {
            return ResponseEntity.ok(LedgerEntryResponse.from(e.getExistingEntry()));
        }
    }

    public record ReversalRequest(String reason, String idempotencyKey) {}

    @PostMapping("/entries/{entryId}/reverse")
    public ResponseEntity<LedgerEntryResponse> reverse(
            @PathVariable UUID accountId,
            @PathVariable UUID entryId,
            @RequestBody ReversalRequest request) {
        verifyOwnership(accountId);
        try {
            LedgerEntry compensating = ledgerService.reverse(
                    accountId, entryId, request.reason(), request.idempotencyKey());
            return ResponseEntity.status(HttpStatus.CREATED).body(LedgerEntryResponse.from(compensating));
        } catch (DuplicateRequestException e) {
            return ResponseEntity.ok(LedgerEntryResponse.from(e.getExistingEntry()));
        }
    }

    @GetMapping("/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable UUID accountId) {
        verifyOwnership(accountId);
        return ResponseEntity.ok(new BalanceResponse(accountId, ledgerService.getCachedBalance(accountId)));
    }

    @GetMapping("/entries")
    public ResponseEntity<List<LedgerEntryResponse>> getHistory(@PathVariable UUID accountId) {
        verifyOwnership(accountId);
        List<LedgerEntryResponse> entries = ledgerService.getHistory(accountId).stream()
                .map(LedgerEntryResponse::from)
                .toList();
        return ResponseEntity.ok(entries);
    }

    @GetMapping("/audit")
    public ResponseEntity<AuditResponse> audit(@PathVariable UUID accountId) {
        verifyOwnership(accountId);
        return ResponseEntity.ok(ledgerService.audit(accountId));
    }
}
