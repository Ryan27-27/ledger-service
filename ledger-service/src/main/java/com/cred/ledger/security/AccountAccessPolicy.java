package com.cred.ledger.security;

import com.cred.ledger.service.exception.AccessDeniedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Authorization rules for ledger operations. A valid JWT proves WHO you are;
 * these checks decide WHAT you may do.
 *
 *  - read / redeem:      the account owner, or an ADMIN
 *  - credit / reverse:   ADMIN only -- these create or undo points, so they
 *                        must come from a trusted actor, not the account holder.
 *                        With app.demo-mode=true the owner may do both on their
 *                        own account so the UI is usable without an admin.
 */
@Component
public class AccountAccessPolicy {

    private final CurrentUser currentUser;
    private final boolean demoMode;

    public AccountAccessPolicy(CurrentUser currentUser, @Value("${app.demo-mode:false}") boolean demoMode) {
        this.currentUser = currentUser;
        this.demoMode = demoMode;
    }

    public void requireAccess(UUID accountId) {
        AuthenticatedUser user = currentUser.get();
        if (!isAdmin(user) && !user.accountId().equals(accountId)) {
            throw new AccessDeniedException("You do not have access to this account");
        }
    }

    public void requireCreditOrReverse(UUID accountId) {
        requireAccess(accountId);
        if (!isAdmin(currentUser.get()) && !demoMode) {
            throw new AccessDeniedException("Only administrators can credit points or reverse entries");
        }
    }

    public void requireAdmin() {
        if (!isAdmin(currentUser.get())) {
            throw new AccessDeniedException("Administrator role required");
        }
    }

    private static boolean isAdmin(AuthenticatedUser user) {
        return "ADMIN".equals(user.role());
    }
}
