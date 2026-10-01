package com.cred.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Two limiters, each available in a Redis-backed (default, correct across
 * instances) and an in-memory (profile "no-redis", for local dev and tests)
 * flavour. Capacity / refill come from app.rate-limit.* in application.yml.
 *
 *  - debitRateLimiter: per account, guards redemption bursts
 *  - authRateLimiter:  per client IP, slows credential stuffing on /auth/**
 */
@Configuration
public class RateLimiterConfig {

    @Bean(name = "debitRateLimiter")
    @Profile("!no-redis")
    public RateLimiter redisDebitRateLimiter(
            StringRedisTemplate redis,
            @Value("${app.rate-limit.debit.capacity:5}") int capacity,
            @Value("${app.rate-limit.debit.refill-per-second:0.5}") double refill) {
        return new RedisTokenBucketRateLimiter(redis, capacity, refill, "ratelimit:debit");
    }

    @Bean(name = "authRateLimiter")
    @Profile("!no-redis")
    public RateLimiter redisAuthRateLimiter(
            StringRedisTemplate redis,
            @Value("${app.rate-limit.auth.capacity:20}") int capacity,
            @Value("${app.rate-limit.auth.refill-per-second:0.5}") double refill) {
        return new RedisTokenBucketRateLimiter(redis, capacity, refill, "ratelimit:auth");
    }

    @Bean(name = "debitRateLimiter")
    @Profile("no-redis")
    public RateLimiter inMemoryDebitRateLimiter(
            @Value("${app.rate-limit.debit.capacity:5}") int capacity,
            @Value("${app.rate-limit.debit.refill-per-second:0.5}") double refill) {
        return new TokenBucketRateLimiter(capacity, refill);
    }

    @Bean(name = "authRateLimiter")
    @Profile("no-redis")
    public RateLimiter inMemoryAuthRateLimiter(
            @Value("${app.rate-limit.auth.capacity:20}") int capacity,
            @Value("${app.rate-limit.auth.refill-per-second:0.5}") double refill) {
        return new TokenBucketRateLimiter(capacity, refill);
    }
}
