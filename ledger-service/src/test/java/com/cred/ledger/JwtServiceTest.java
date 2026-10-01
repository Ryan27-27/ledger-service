package com.cred.ledger;

import com.cred.ledger.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure unit tests: no Spring context, no Docker. */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes-long";

    @Test
    void issuedToken_roundTripsItsClaims() {
        JwtService jwt = new JwtService(SECRET, 60);
        UUID accountId = UUID.randomUUID();

        Claims claims = jwt.parseAndValidate(jwt.issueToken("alice", accountId, "USER"));

        assertEquals("alice", claims.getSubject());
        assertEquals(accountId.toString(), claims.get("accountId", String.class));
        assertEquals("USER", claims.get("role", String.class));
    }

    @Test
    void expiredToken_isRejected() {
        JwtService jwt = new JwtService(SECRET, -10);
        String token = jwt.issueToken("alice", UUID.randomUUID(), "USER");

        assertThrows(JwtException.class, () -> jwt.parseAndValidate(token));
    }

    @Test
    void tokenSignedWithAnotherKey_isRejected() {
        String forged = new JwtService("a-completely-different-secret-of-32-bytes!!", 60)
                .issueToken("mallory", UUID.randomUUID(), "ADMIN");

        assertThrows(JwtException.class, () -> new JwtService(SECRET, 60).parseAndValidate(forged));
    }

    @Test
    void tokenWithSwappedPayload_isRejected() {
        JwtService jwt = new JwtService(SECRET, 60);
        String[] user = jwt.issueToken("alice", UUID.randomUUID(), "USER").split("\\.");
        String[] admin = jwt.issueToken("bob", UUID.randomUUID(), "ADMIN").split("\\.");

        // alice's header + bob's (admin) payload + alice's signature: signature no longer matches
        String forged = user[0] + "." + admin[1] + "." + user[2];

        assertThrows(JwtException.class, () -> jwt.parseAndValidate(forged));
    }

    @Test
    void shortSecret_isRefusedAtStartup() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 60));
    }
}
