package com.cred.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RateLimiterConfig {

    /**
     * Default: Redis-backed, correct across multiple instances. Active
     * whenever the "no-redis" profile is NOT set.
     *
     * capacity=5, refill=0.5/sec -> an account can burst 5 redemption
     * attempts, then is limited to 1 every 2 seconds.
     */
    @Bean
    @Profile("!no-redis")
    public RateLimiter redisDebitRateLimiter(StringRedisTemplate redisTemplate) {
        return new RedisTokenBucketRateLimiter(redisTemplate, 5, 0.5, "ratelimit:debit");
    }

    /**
     * Fallback for local dev without Redis running, or for the test
     * profile: `mvn test -Dspring-boot.run.profiles=no-redis`. Same
     * capacity/refill so behavior is identical, just not distributed.
     */
    @Bean
    @Profile("no-redis")
    public RateLimiter inMemoryDebitRateLimiter() {
        return new TokenBucketRateLimiter(5, 0.5);
    }
}
