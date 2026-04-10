package com.example.redis.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <b>Caching Pattern Infrastructure Configuration</b>
 *
 * <p>Provides shared infrastructure beans used across all caching pattern implementations: a thread pool for
 * asynchronous write-back / refresh-ahead operations, and a Caffeine-based in-process L1 cache for multi-level
 * caching strategies.
 *
 * <pre>
 *  ┌──────────┐    ┌────────────────┐    ┌───────────┐    ┌────────────┐
 *  │ Request  │───>│ Caffeine (L1)  │───>│ Redis(L2) │───>│ PostgreSQL │
 *  └──────────┘    └────────────────┘    └───────────┘    └────────────┘
 *                  in-process cache        remote cache       database
 * </pre>
 */
@Slf4j
@Configuration
public class CachingPatternConfig {

    /**
     * <b>Caching Pattern Executor</b>
     *
     * <p>Fixed-size thread pool used by write-back and refresh-ahead patterns to perform asynchronous DB writes and
     * cache refreshes without blocking the caller.
     *
     * @param poolSize thread pool size from {@code app.caching.executor-pool-size} (default 4)
     * @return a fixed thread pool {@link ExecutorService}
     */
    @Bean("cachingPatternExecutor")
    public ExecutorService cachingPatternExecutor(
            @Value("${app.caching.executor-pool-size:4}") int poolSize) {
        return Executors.newFixedThreadPool(poolSize);
    }

    /**
     * <b>Caffeine L1 Cache</b>
     *
     * <p>In-process Caffeine cache acting as the L1 layer in multi-level caching strategies. Configured with a
     * bounded maximum size, time-based expiration, and statistics recording for observability of hit/miss ratios.
     *
     * @param maxSize       maximum number of entries from {@code app.caching.caffeine.max-size}
     * @param expireSeconds TTL in seconds from {@code app.caching.caffeine.expire-after-write-seconds}
     * @return a stats-enabled {@link Cache} instance
     */
    @Bean("caffeineL1Cache")
    public Cache<String, String> caffeineL1Cache(
            @Value("${app.caching.caffeine.max-size:100}") long maxSize,
            @Value("${app.caching.caffeine.expire-after-write-seconds:300}") long expireSeconds,
            @Qualifier("cachingPatternExecutor") ExecutorService executor) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .executor(executor)
                .recordStats()
                .removalListener((String key, String value, RemovalCause cause) ->
                        log.debug("L1 evicted: key={} cause={}", key, cause))
                .build();
    }
}
