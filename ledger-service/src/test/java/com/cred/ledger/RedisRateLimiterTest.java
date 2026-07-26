package com.cred.ledger;

import com.cred.ledger.config.RedisTokenBucketRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spins up a real Redis via Testcontainers (not @SpringBootTest -- this
 * talks to RedisTokenBucketRateLimiter directly, so it's fast and doesn't
 * need the full app context).
 *
 * Two things worth proving:
 * 1. Basic correctness: a bucket of capacity N allows exactly N requests
 *    in a burst, then rejects.
 * 2. Atomicity under concurrency: firing far more requests than the
 *    capacity, all at once, from multiple threads, never allows more than
 *    `capacity` through -- this is what the Lua script buys over a
 *    naive GET-then-SET from Java, which would race.
 */
@Testcontainers
class RedisRateLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
    }

    @Test
    void burstUpToCapacity_thenRejects() {
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redisTemplate, 5, 0.1, "test:ratelimit");
        String key = "account-" + UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryConsume(key), "request " + i + " should be allowed within capacity");
        }
        assertFalse(limiter.tryConsume(key), "6th request should be rejected, bucket is empty");
    }

    @Test
    void concurrentBurst_neverExceedsCapacity() throws InterruptedException {
        int capacity = 5;
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redisTemplate, capacity, 0.1, "test:ratelimit");
        String key = "account-" + UUID.randomUUID();

        int threadCount = 50; // 10x the capacity, all racing for the same bucket
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (limiter.tryConsume(key)) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(capacity, allowedCount.get(),
                "exactly `capacity` requests should be allowed, no matter how many raced for the bucket");
    }
}
