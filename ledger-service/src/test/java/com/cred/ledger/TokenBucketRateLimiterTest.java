package com.cred.ledger;

import com.cred.ledger.config.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests for the in-memory limiter: no Spring context, no Docker. */
class TokenBucketRateLimiterTest {

    @Test
    void allowsABurstUpToCapacity_thenRejects() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 0.0);

        assertTrue(limiter.tryConsume("a"));
        assertTrue(limiter.tryConsume("a"));
        assertTrue(limiter.tryConsume("a"));
        assertFalse(limiter.tryConsume("a"));
    }

    @Test
    void keysHaveIndependentBuckets() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 0.0);

        assertTrue(limiter.tryConsume("a"));
        assertFalse(limiter.tryConsume("a"));
        assertTrue(limiter.tryConsume("b"));
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 100.0); // 1 token per 10 ms

        assertTrue(limiter.tryConsume("a"));
        Thread.sleep(50);
        assertTrue(limiter.tryConsume("a"));
    }

    @Test
    void neverExceedsCapacityUnderConcurrency() throws InterruptedException {
        int capacity = 10;
        int threads = 100;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 0.0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    if (limiter.tryConsume("shared")) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(capacity, allowed.get());
    }
}
