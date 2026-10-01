package com.cred.ledger.service;

import com.cred.ledger.domain.Account;
import com.cred.ledger.domain.RefreshToken;
import com.cred.ledger.domain.User;
import com.cred.ledger.dto.AuthResponse;
import com.cred.ledger.dto.LoginRequest;
import com.cred.ledger.dto.RegisterRequest;
import com.cred.ledger.repository.AccountRepository;
import com.cred.ledger.repository.RefreshTokenRepository;
import com.cred.ledger.repository.UserRepository;
import com.cred.ledger.security.JwtService;
import com.cred.ledger.service.exception.InvalidCredentialsException;
import com.cred.ledger.service.exception.InvalidRefreshTokenException;
import com.cred.ledger.service.exception.UsernameAlreadyExistsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final long refreshExpirySeconds;

    /** Compared against when the username doesn't exist, so timing doesn't reveal which usernames are real. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, AccountRepository accountRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder, JwtService jwtService,
                       @Value("${app.jwt.refresh-expiry-seconds:604800}") long refreshExpirySeconds) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshExpirySeconds = refreshExpirySeconds;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    /**
     * Registration provisions both a User (auth identity) and an Account
     * (ledger identity) atomically. Usernames are case-insensitive (stored
     * lower-case). The unique constraint is the real guard against a
     * concurrent duplicate; the existsBy check just gives a friendly error.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = normalize(request.username());
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException("Username already taken: " + username);
        }

        Account account = accountRepository.save(new Account(username));
        User user = userRepository.save(new User(username, passwordEncoder.encode(request.password()), account.getId()));
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByUsername(normalize(request.username()));

        // Always run one bcrypt comparison, whether or not the user exists.
        String hash = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordOk = passwordEncoder.matches(request.password(), hash);

        if (found.isEmpty() || !passwordOk) {
            throw new InvalidCredentialsException("Invalid username or password");
        }
        return issueTokens(found.get());
    }

    /**
     * Rotating refresh: every use revokes the presented token and issues a new
     * pair. Presenting an already-used token means it was replayed (likely
     * stolen), so every session for that user is revoked. noRollbackFor keeps
     * that revocation from being rolled back by the exception we throw.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        if (stored.isRevoked()) {
            refreshTokenRepository.revokeAllForUser(stored.getUserId());
            throw new InvalidRefreshTokenException("Refresh token has already been used");
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Refresh token expired");
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);
        return issueTokens(user);
    }

    /** Revokes the given refresh token. Unknown tokens are ignored (logout is idempotent). */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(sha256Hex(rawRefreshToken)).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokenRepository.save(t);
        });
    }

    /** Used by the first-run admin bootstrap. @return true if a new admin was created. */
    @Transactional
    public boolean createAdminIfAbsent(String username, String password) {
        String normalized = normalize(username);
        if (userRepository.existsByUsername(normalized)) {
            return false;
        }
        Account account = accountRepository.save(new Account(normalized));
        User admin = new User(normalized, passwordEncoder.encode(password), account.getId());
        admin.setRole("ADMIN");
        userRepository.save(admin);
        return true;
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.issueToken(user.getUsername(), user.getAccountId(), user.getRole());

        String refreshToken = newOpaqueToken();
        refreshTokenRepository.save(new RefreshToken(
                user.getId(), sha256Hex(refreshToken), Instant.now().plusSeconds(refreshExpirySeconds)));

        return AuthResponse.of(accessToken, jwtService.getExpirySeconds(), refreshToken,
                user.getAccountId(), user.getUsername(), user.getRole());
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", e);
        }
    }
}
