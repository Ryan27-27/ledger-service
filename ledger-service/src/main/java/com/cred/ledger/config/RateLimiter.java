package com.cred.ledger.config;

/**
 * Strategy interface so the app can run with either backing store without
 * touching the controller. Local dev / tests use the in-memory
 * TokenBucketRateLimiter; anything with more than one instance should use
 * RedisTokenBucketRateLimiter (selected by the "redis-rate-limit" profile,
 * see application.yml).
 */
public interface RateLimiter {
    /** @return true if the request is allowed (a token was consumed) */
    boolean tryConsume(String key);
}
