package com.cred.ledger.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Auth identity, separate from Account (the ledger's domain entity).
 * One user owns exactly one account in this simplified model -- a real
 * system might support multiple accounts per user (family plans, business
 * accounts, etc.), which is exactly why the two are kept as separate
 * entities instead of bolting username/password onto Account directly.
 */
@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, unique = true)
    private UUID accountId;

    @Column(nullable = false)
    private String role = "USER";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public User(String username, String passwordHash, UUID accountId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.accountId = accountId;
    }
}
