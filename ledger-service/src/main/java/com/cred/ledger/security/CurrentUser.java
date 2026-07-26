package com.cred.ledger.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    public AuthenticatedUser get() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedUser user) {
            return user;
        }
        throw new IllegalStateException("No authenticated user in context");
    }
}
