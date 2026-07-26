package com.cred.ledger.controller;

import com.cred.ledger.domain.Account;
import com.cred.ledger.security.CurrentUser;
import com.cred.ledger.service.LedgerService;
import com.cred.ledger.service.exception.AccessDeniedException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Direct account creation, outside of registration -- e.g. an ops tool
 * provisioning an account without a login (a service account, or a
 * migration script). Restricted to ADMIN since normal users get an
 * account automatically via POST /auth/register.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final LedgerService ledgerService;
    private final CurrentUser currentUser;

    public AccountController(LedgerService ledgerService, CurrentUser currentUser) {
        this.ledgerService = ledgerService;
        this.currentUser = currentUser;
    }

    public record CreateAccountRequest(@NotBlank String userId) {}

    @PostMapping
    public ResponseEntity<Account> createAccount(@RequestBody CreateAccountRequest request) {
        if (!"ADMIN".equals(currentUser.get().role())) {
            throw new AccessDeniedException("Only admins can create accounts directly; use /auth/register instead");
        }
        Account account = ledgerService.createAccount(request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }
}
