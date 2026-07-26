package com.cred.ledger;

import com.cred.ledger.domain.Account;
import com.cred.ledger.domain.EntryType;
import com.cred.ledger.dto.LedgerEntryRequest;
import com.cred.ledger.repository.AccountRepository;
import com.cred.ledger.service.LedgerService;
import com.cred.ledger.service.exception.DuplicateRequestException;
import com.cred.ledger.service.exception.InsufficientBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the core interview claim: "two concurrent redemption requests against
 * the same account cannot both succeed if only one can be afforded."
 *
 * Seeds an account with balance = 100, then fires N concurrent debit requests
 * of 100 each. Because debit() takes a pessimistic row lock (SELECT ... FOR
 * UPDATE), the requests serialize -- exactly ONE should succeed and the rest
 * should fail with InsufficientBalanceException. If the lock were missing,
 * multiple requests could read balance=100 before any of them writes,
 * and more than one would succeed (double-spend).
 *
 * Uses the "no-redis" profile since this test calls LedgerService directly
 * (bypassing the controller and its rate limiter), so it has no need for a
 * live Redis instance -- see RedisRateLimiterTest for that.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("no-redis")
class LedgerConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        Account account = ledgerService.createAccount("user-" + UUID.randomUUID());
        accountId = account.getId();
        ledgerService.credit(accountId, new LedgerEntryRequest(
                new BigDecimal("100.00"), "seed-credit", "seed-" + UUID.randomUUID()));
    }

    @Test
    void concurrentDebits_onlyOneSucceeds_noDoubleSpend() throws InterruptedException {
        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startGate.await(); // all threads fire as close to simultaneously as possible
                    ledgerService.debit(accountId, new LedgerEntryRequest(
                            new BigDecimal("100.00"), "redemption-" + idx, "redeem-" + UUID.randomUUID()));
                    successCount.incrementAndGet();
                } catch (InsufficientBalanceException | ObjectOptimisticLockingFailureException e) {
                    failureCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads at once
        doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, successCount.get(), "exactly one debit should succeed");
        assertEquals(threadCount - 1, failureCount.get(), "the rest should fail on insufficient balance");

        BigDecimal finalBalance = ledgerService.getCachedBalance(accountId);
        assertEquals(0, finalBalance.compareTo(BigDecimal.ZERO), "balance should be exactly 0, never negative");

        // Ground-truth check: replaying the append-only log agrees with the cache
        var audit = ledgerService.audit(accountId);
        assertTrue(audit.consistent(), "cached balance must match replayed ledger total");
    }

    @Test
    void duplicateIdempotencyKey_isRejectedNotDoubleApplied() {
        String key = "fixed-key-" + UUID.randomUUID();
        var req = new LedgerEntryRequest(new BigDecimal("10.00"), "bonus", key);

        ledgerService.credit(accountId, req);
        try {
            ledgerService.credit(accountId, req); // simulate client retry with same key
        } catch (DuplicateRequestException ignored) {
            // expected
        }

        BigDecimal balance = ledgerService.getCachedBalance(accountId);
        // 100 (seed) + 10 (single credit applied once, not twice)
        assertEquals(0, balance.compareTo(new BigDecimal("110.00")));
    }
}
