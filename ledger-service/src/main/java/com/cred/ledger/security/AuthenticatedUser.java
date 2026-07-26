package com.cred.ledger.security;

import java.util.UUID;

/**
 * The authenticated principal placed into the SecurityContext by
 * JwtAuthenticationFilter. Controllers read accountId off this (via
 * CurrentUser) to check resource ownership instead of trusting the
 * {accountId} path variable blindly.
 */
public record AuthenticatedUser(String username, UUID accountId, String role) {
}
