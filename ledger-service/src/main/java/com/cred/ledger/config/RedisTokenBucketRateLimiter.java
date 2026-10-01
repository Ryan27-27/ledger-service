package com.cred.ledger.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Distributed version of TokenBucketRateLimiter. Same token-bucket
 * semantics (capacity + refill rate), but the bucket state lives in Redis
 * instead of a local HashMap, so the limit is enforced correctly across
 * however many instances of this service are running behind a load
 * balancer -- an in-memory limiter would let each instance give out its
 * own full quota, effectively multiplying the real limit by instance count.
 *
 * The read-refill-check-decrement sequence runs as a single Lua script
 * server-side in Redis, which is single-threaded per key -- that's what
 * makes this atomic across concurrent requests hitting different app
 * instances at the same moment, without needing a distributed lock.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> script;
    private final int capacity;
    private final double refillTokensPerSecond;
    private final String keyPrefix;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate redisTemplate,
            int capacity,
            double refillTokensPerSecond,
            String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.keyPrefix = keyPrefix;
        this.script = new DefaultRedisScript<>(
                loadScript(), Long.class);
    }

    private static String loadScript() {
        try {
            return new String(new ClassPathResource("scripts/token_bucket.lua").getInputStream().readAllBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load token_bucket.lua", e);
        }
    }

    @Override
    public boolean tryConsume(String key) {
        String bucketKey = keyPrefix + ":" + key;
        double nowSeconds = System.currentTimeMillis() / 1000.0;

        try {
            Long result = redisTemplate.execute(
                    script,
                    List.of(bucketKey),
                    String.valueOf(capacity),
                    String.valueOf(refillTokensPerSecond),
                    String.valueOf(nowSeconds),
                    "1"
            );
            return result != null && result == 1L;
        } catch (RuntimeException e) {
            // Fail OPEN: a Redis outage must not take the ledger down with it. The
            // financial invariants (locking, idempotency, balance checks) live in
            // Postgres and do not depend on the limiter.
            log.warn("Rate limiter unavailable ({}); allowing request for key {}", e.getMessage(), bucketKey);
            return true;
        }
    }
}
