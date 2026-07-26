package com.cred.ledger.dto;

import java.util.UUID;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        UUID accountId,
        String username
) {
    public static AuthResponse of(String token, long expiresInSeconds, UUID accountId, String username) {
        return new AuthResponse(token, "Bearer", expiresInSeconds, accountId, username);
    }
}
