package com.cred.ledger;

import com.cred.ledger.domain.Account;
import com.cred.ledger.domain.EntryStatus;
import com.cred.ledger.domain.LedgerEntry;
import com.cred.ledger.dto.LedgerEntryRequest;
import com.cred.ledger.service.LedgerService;
import com.cred.ledger.service.exception.DuplicateRequestException;
import com.cred.ledger.service.exception.IdempotencyKeyReuseException;
import com.cred.ledger.service.exception.InsufficientBalanceException;
import com.cred.ledger.service.exception.InvalidReversalException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level correctness tests against a real Postgres: reversals, audit
 * consistency, idempotency semantics, and the two locking strategies under
 * genuine concurrency.
 */
@Testcontainers(disabledWithoutDocker = true)
class LedgerServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerService ledger;

    private UUID newAccount() {
        Account account = ledger.createAccount("user-" + UUID.randomUUID());
        return account.getId();
    }

    private static LedgerEntryRequest request(String amount, String reference) {
        return new LedgerEntryRequest(new BigDecimal(amount), reference, "key-" + UUID.randomUUID());
    }

    private void assertBalance(UUID accountId, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(ledger.getCachedBalance(accountId)),
                "cached balance should be " + expected);
        var audit = ledger.audit(accountId);
        assertTrue(audit.consistent(), "cached " + audit.cachedBalance() + " vs replayed " + audit.computedBalance());
    }

    // ------------------------------------------------------------ reversals

    @Test
    void reversingACredit_removesThePoints_andAuditStaysConsistent() {
        UUID account = newAccount();
        ledger.credit(account, request("100.00", "seed"));
        LedgerEntry bonus = ledger.credit(account, request("40.00", "bonus"));
        assertBalance(account, "140.00");

        LedgerEntry compensation = ledger.reverse(account, bonus.getId(), "granted in error", "rev-" + UUID.randomUUID());

        assertEquals(bonus.getId(), compensation.getReversalOf());
        assertEquals("granted in error", compensation.getRemarks());
        assertBalance(account, "100.00");
    }

    @Test
    void reversingADebit_refundsThePoints_andAuditStaysConsistent() {
        UUID account = newAccount();
        ledger.credit(account, request("100.00", "seed"));
        LedgerEntry redemption = ledger.debit(account, request("60.00", "voucher"));
        assertBalance(account, "40.00");

        ledger.reverse(account, redemption.getId(), "voucher failed", "rev-" + UUID.randomUUID());

        assertBalance(account, "100.00");
    }

    @Test
    void anEntryCanOnlyBeReversedOnce() {
        UUID account = newAccount();
        LedgerEntry credit = ledger.credit(account, request("50.00", "seed"));
        ledger.reverse(account, credit.getId(), "first", "rev-" + UUID.randomUUID());

        assertThrows(InvalidReversalException.class,
                () -> ledger.reverse(account, credit.getId(), "second", "rev-" + UUID.randomUUID()));
        assertBalance(account, "0.00");
    }

    @Test
    void aReversalEntryCannotItselfBeReversed() {
        UUID account = newAccount();
        LedgerEntry credit = ledger.credit(account, request("50.00", "seed"));
        LedgerEntry compensation = ledger.reverse(account, credit.getId(), "oops", "rev-" + UUID.randomUUID());

        assertThrows(InvalidReversalException.class,
                () -> ledger.reverse(account, compensation.getId(), "undo the undo", "rev-" + UUID.randomUUID()));
    }

    @Test
    void reversingACredit_failsWhenThosePointsWereAlreadySpent() {
        UUID account = newAccount();
        LedgerEntry credit = ledger.credit(account, request("100.00", "seed"));
        ledger.debit(account, request("60.00", "voucher"));

        assertThrows(InsufficientBalanceException.class,
                () -> ledger.reverse(account, credit.getId(), "too late", "rev-" + UUID.randomUUID()));

        // the failed reversal rolled back completely: nothing changed
        assertBalance(account, "40.00");
    }

    @Test
    void theOriginalIsMarkedReversed_andItsFinancialFieldsAreUntouched() {
        UUID account = newAccount();
        LedgerEntry credit = ledger.credit(account, request("25.00", "seed"));
        ledger.reverse(account, credit.getId(), "oops", "rev-" + UUID.randomUUID());

        var page = ledger.getHistory(account, 0, 10);
        assertEquals(2, page.totalElements());
        var original = page.items().stream().filter(e -> e.id().equals(credit.getId())).findFirst().orElseThrow();
        assertEquals(EntryStatus.REVERSED, original.status());
        assertEquals(0, new BigDecimal("25.00").compareTo(original.amount()));
    }

    // ---------------------------------------------------------- idempotency

    @Test
    void replayingTheSameRequest_signalsDuplicate_andAppliesItOnce() {
        UUID account = newAccount();
        var req = request("10.00", "bonus");
        LedgerEntry first = ledger.credit(account, req);

        DuplicateRequestException dup = assertThrows(DuplicateRequestException.class, () -> ledger.credit(account, req));

        assertEquals(first.getId(), dup.getExistingEntry().getId());
        assertBalance(account, "10.00");
    }

    @Test
    void reusingAKeyWithADifferentAmount_isRejected() {
        UUID account = newAccount();
        String key = "shared-" + UUID.randomUUID();
        ledger.credit(account, new LedgerEntryRequest(new BigDecimal("10.00"), "bonus", key));

        assertThrows(IdempotencyKeyReuseException.class,
                () -> ledger.credit(account, new LedgerEntryRequest(new BigDecimal("99.00"), "bonus", key)));
        assertBalance(account, "10.00");
    }

    @Test
    void theSameKeyOnTwoDifferentAccounts_doesNotCollide() {
        UUID a = newAccount();
        UUID b = newAccount();
        String key = "shared-" + UUID.randomUUID();

        ledger.credit(a, new LedgerEntryRequest(new BigDecimal("10.00"), "bonus", key));
        ledger.credit(b, new LedgerEntryRequest(new BigDecimal("20.00"), "bonus", key));

        assertBalance(a, "10.00");
        assertBalance(b, "20.00");
    }

    // ---------------------------------------------------------- concurrency

    @Test
    void concurrentDebits_onlyOneSucceeds_noDoubleSpend() throws Exception {
        UUID account = newAccount();
        ledger.credit(account, request("100.00", "seed"));

        int threads = 8;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(threads, () -> {
            try {
                ledger.debit(account, request("100.00", "redemption"));
                succeeded.incrementAndGet();
            } catch (InsufficientBalanceException e) {
                insufficient.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertTrue(unexpected.isEmpty(), "unexpected failures: " + unexpected);
        assertEquals(1, succeeded.get(), "exactly one debit should succeed");
        assertEquals(threads - 1, insufficient.get(), "every other debit should fail on insufficient balance");
        assertBalance(account, "0.00");
    }

    @Test
    void concurrentCredits_neverLoseAnUpdate() throws Exception {
        UUID account = newAccount();

        int threads = 8;
        AtomicInteger succeeded = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(threads, () -> {
            try {
                ledger.credit(account, request("5.00", "bonus"));
                succeeded.incrementAndGet();
            } catch (OptimisticLockingFailureException e) {
                // expected under contention: the controller retries these
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertTrue(unexpected.isEmpty(), "unexpected failures: " + unexpected);
        assertTrue(succeeded.get() >= 1, "at least one credit must win");
        // the invariant that matters: every credit that reported success is in the balance, none lost or doubled
        assertBalance(account, new BigDecimal("5.00").multiply(BigDecimal.valueOf(succeeded.get())).toPlainString());
        assertNotEquals(0, succeeded.get());
    }

    private static void runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    task.run();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();
    }
}
