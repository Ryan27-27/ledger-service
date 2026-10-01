package com.cred.ledger.dto;

import java.util.UUID;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        String refreshToken,
        UUID accountId,
        String username,
        String role
) {
    public static AuthResponse of(String token, long expiresInSeconds, String refreshToken,
                                  UUID accountId, String username, String role) {
        return new AuthResponse(token, "Bearer", expiresInSeconds, refreshToken, accountId, username, role);
    }
}
