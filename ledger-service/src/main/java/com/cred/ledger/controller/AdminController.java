package com.cred.ledger.controller;

import com.cred.ledger.dto.AccountSummary;
import com.cred.ledger.dto.PageResponse;
import com.cred.ledger.security.AccountAccessPolicy;
import com.cred.ledger.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "ADMIN role only")
public class AdminController {

    private final AdminService adminService;
    private final AccountAccessPolicy policy;

    public AdminController(AdminService adminService, AccountAccessPolicy policy) {
        this.adminService = adminService;
        this.policy = policy;
    }

    @Operation(summary = "List accounts (with username and balance), optionally filtered by username")
    @GetMapping("/accounts")
    public ResponseEntity<PageResponse<AccountSummary>> listAccounts(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        policy.requireAdmin(); // URL rule already enforces this; kept as defence in depth
        return ResponseEntity.ok(adminService.listAccounts(q, Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }
}
