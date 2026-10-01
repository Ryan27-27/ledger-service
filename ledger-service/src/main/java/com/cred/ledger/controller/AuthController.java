package com.cred.ledger.controller;

import com.cred.ledger.config.RateLimiter;
import com.cred.ledger.dto.AuthResponse;
import com.cred.ledger.dto.LoginRequest;
import com.cred.ledger.dto.RefreshRequest;
import com.cred.ledger.dto.RegisterRequest;
import com.cred.ledger.service.AuthService;
import com.cred.ledger.service.exception.RateLimitExceededException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Register, login, rotating refresh tokens")
public class AuthController {

    private final AuthService authService;
    private final RateLimiter authRateLimiter;

    public AuthController(AuthService authService, @Qualifier("authRateLimiter") RateLimiter authRateLimiter) {
        this.authService = authService;
        this.authRateLimiter = authRateLimiter;
    }

    @Operation(summary = "Create a user + points account and return tokens")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest http) {
        throttle(http);
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        throttle(http);
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "Exchange a refresh token for a new access + refresh token pair (old one is revoked)")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        throttle(http);
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "Revoke a refresh token")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private void throttle(HttpServletRequest http) {
        if (!authRateLimiter.tryConsume(http.getRemoteAddr())) {
            throw new RateLimitExceededException("Too many authentication attempts, please slow down");
        }
    }
}
