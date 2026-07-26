package com.cred.ledger.config;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory token bucket, keyed per account. Good enough for a single
 * instance / demo; swap the backing map for Redis (INCR + EXPIRE, or a Lua
 * script for atomicity) the moment this runs on more than one instance,
 * since in-memory state doesn't share across pods.
 *
 * Deliberately kept dependency-free (no bucket4j/Resilience4j) so the
 * mechanism -- refill rate, bucket capacity, atomic check-and-decrement --
 * is visible and easy to explain in an interview instead of hidden in a
 * library.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final int capacity;
    private final double refillTokensPerSecond;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, double refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
    }

    /** @return true if the request is allowed (a token was consumed) */
    @Override
    public boolean tryConsume(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        return bucket.tryConsume(refillTokensPerSecond, capacity);
    }

    private static class Bucket {
        final AtomicLong microTokens; // tokens scaled by 1_000_000 to avoid floating point in the hot path
        volatile long lastRefillNanos;

        Bucket(int capacity) {
            this.microTokens = new AtomicLong((long) capacity * 1_000_000L);
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(double refillPerSecond, int capacity) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            long refillMicroTokens = (long) (elapsedSeconds * refillPerSecond * 1_000_000L);
            if (refillMicroTokens > 0) {
                long cappedMax = (long) capacity * 1_000_000L;
                microTokens.set(Math.min(cappedMax, microTokens.get() + refillMicroTokens));
                lastRefillNanos = now;
            }
            if (microTokens.get() >= 1_000_000L) {
                microTokens.addAndGet(-1_000_000L);
                return true;
            }
            return false;
        }
    }
}
