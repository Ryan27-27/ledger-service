package com.cred.ledger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN from APP_ADMIN_USERNAME / APP_ADMIN_PASSWORD so the
 * admin-only endpoints are usable on a fresh database. No-op when unset.
 * Admins can never be created through the public /auth/register endpoint.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthService authService;
    private final String username;
    private final String password;

    public AdminBootstrap(AuthService authService,
                          @Value("${app.admin.username:}") String username,
                          @Value("${app.admin.password:}") String password) {
        this.authService = authService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank()) {
            return;
        }
        if (password.length() < 8) {
            log.error("APP_ADMIN_PASSWORD must be at least 8 characters; admin bootstrap skipped");
            return;
        }
        try {
            if (authService.createAdminIfAbsent(username, password)) {
                log.info("Bootstrapped admin user '{}'", username.trim().toLowerCase());
            }
        } catch (DataIntegrityViolationException e) {
            log.info("Admin '{}' was created concurrently by another instance", username);
        }
    }
}
