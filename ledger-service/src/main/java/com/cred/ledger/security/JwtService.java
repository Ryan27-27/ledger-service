package com.cred.ledger.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates HS256 JWTs. The token embeds accountId as a custom
 * claim so every downstream request can be checked against the resource
 * it's touching (see JwtAuthenticationFilter + LedgerController's ownership
 * check) without a DB lookup on every call.
 *
 * Swapping this for an OAuth2 resource-server setup (validating tokens
 * issued by Auth0/Okta/Cognito instead of signing them ourselves) means:
 * replace this class with Spring Security's `oauth2ResourceServer().jwt()`
 * config pointing at the IdP's JWK Set URI, and read the same accountId
 * claim (or a custom claim mapped by the IdP) in the filter. The rest of
 * the app -- the ownership check, the controllers -- doesn't change.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiry-seconds:3600}") long expirySeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expirySeconds = expirySeconds;
    }

    public String issueToken(String username, UUID accountId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirySeconds * 1000);

        return Jwts.builder()
                .subject(username)
                .claim("accountId", accountId.toString())
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public long getExpirySeconds() {
        return expirySeconds;
    }

    /** @throws io.jsonwebtoken.JwtException if the token is malformed, expired, or signed with a different key */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
