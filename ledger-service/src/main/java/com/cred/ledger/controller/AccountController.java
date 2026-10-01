package com.cred.ledger.controller;

import com.cred.ledger.domain.Account;
import com.cred.ledger.security.AccountAccessPolicy;
import com.cred.ledger.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Direct account creation, outside of registration -- e.g. an ops tool
 * provisioning an account without a login (a service account, or a
 * migration script). ADMIN only, since normal users get an account
 * automatically via POST /auth/register.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final LedgerService ledgerService;
    private final AccountAccessPolicy policy;

    public AccountController(LedgerService ledgerService, AccountAccessPolicy policy) {
        this.ledgerService = ledgerService;
        this.policy = policy;
    }

    public record CreateAccountRequest(@NotBlank @Size(max = 255) String userId) {}

    public record AccountResponse(UUID id, String userId, BigDecimal balance, Instant createdAt) {
        static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getUserId(), a.getCachedBalance(), a.getCreatedAt());
        }
    }

    @Operation(summary = "Provision an account without a login (admin only)")
    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        policy.requireAdmin();
        Account account = ledgerService.createAccount(request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }
}
