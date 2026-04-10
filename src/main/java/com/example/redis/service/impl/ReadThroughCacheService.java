package com.example.redis.service.impl;

import com.example.redis.dto.CacheEntry;
import com.example.redis.dto.CacheStateResponse;
import com.example.redis.dto.CachingPatternStateResponse;
import com.example.redis.service.CachingPatternService;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <b>Read-Through Cache Service</b>
 *
 * <p>Uses Caffeine's {@link LoadingCache} for built-in stampede protection.
 * When multiple threads request the same missing key concurrently, Caffeine
 * ensures only ONE thread calls the loader — all others block and receive
 * the same result. No manual SETNX locks or polling needed.
 *
 * <p>Read flow:
 * <pre>
 *  +---------+  GET   +---------------------------+  MISS  +----------+
 *  |   App   |-------&gt;| Caffeine LoadingCache     |-------&gt;| L2 Redis |
 *  +---------+        | (auto coalesces requests) |        +----+-----+
 *       ^             +---------------------------+         MISS |
 *       |                                                 +-----v------+
 *       +---- return (single load, shared result)         | PostgreSQL |
 *                                                         +------------+
 * </pre>
 *
 * @see CachingPatternService
 * @see LoadingCache
 */
@Slf4j
@Service("readThroughService")
public class ReadThroughCacheService implements CachingPatternService {

    private static final String L2_KEY = "pattern:read-through:cache";
    private static final String META_KEY = "pattern:read-through:meta";
    private static final String PATTERN = "read-through";

    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseService database;
    private final long expireSeconds;
    private LoadingCache<String, String> caffeineL1Cache;

    public ReadThroughCacheService(
            RedisTemplate<String, String> redisTemplate,
            DatabaseService database,
            @Value("${app.caching.caffeine.expire-after-write-seconds:300}") long expireSeconds) {
        this.redisTemplate = redisTemplate;
        this.database = database;
        this.expireSeconds = expireSeconds;
    }

    /**
     * <b>Initialize Caffeine LoadingCache with stampede protection</b>
     *
     * <p>Caffeine's {@code LoadingCache.get()} guarantees that only one thread
     * executes the loader per key. All concurrent callers for the same key
     * block until the single loader completes, then receive the same result.
     */
    @PostConstruct
    public void initCache() {
        this.caffeineL1Cache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build(this::loadForCaffeine);
        log.info("READ-THROUGH: Caffeine LoadingCache initialized "
                + "(expire={}s, stampede protection enabled)", expireSeconds);
    }

    /**
     * <b>Caffeine CacheLoader — L2 then DB</b>
     *
     * <p>Called by Caffeine on cache miss. Only one thread per key executes
     * this — all concurrent requests for the same key wait for this result.
     * Checks L2 (Redis) first, falls back to PostgreSQL.
     */
    private String loadForCaffeine(String l1Key) {
        String key = l1Key.startsWith(PATTERN + ":")
                ? l1Key.substring(PATTERN.length() + 1) : l1Key;

        Object l2Value = redisTemplate.opsForHash().get(L2_KEY, key);
        if (l2Value != null) {
            incrementMeta("l2-hits");
            log.info("READ-THROUGH loader: L2 HIT key={}", key);
            return l2Value.toString();
        }

        incrementMeta("db-loads");
        String dbValue = database.read(PATTERN, key);
        if (dbValue != null) {
            redisTemplate.opsForHash().put(L2_KEY, key, dbValue);
            log.info("READ-THROUGH loader: DB fetch key={}, cached in L2", key);
        }
        return dbValue;
    }

    /**
     * <b>Read-Through WRITE</b>
     *
     * <p>Updates DB and invalidates L1 + L2. Read-through is a read-side pattern,
     * so writes use cache-aside invalidation.
     *
     * @param key   cache key to write
     * @param value value to persist
     */
    @Override
    public void put(String key, String value) {
        database.write(PATTERN, key, value);
        redisTemplate.opsForHash().delete(L2_KEY, key);
        caffeineL1Cache.invalidate(PATTERN + ":" + key);
        log.info("READ-THROUGH write: DB updated, L1+L2 invalidated for key={}", key);
    }

    /**
     * <b>Read-Through READ (stampede-safe via Caffeine LoadingCache)</b>
     *
     * <p>Delegates to {@code caffeineL1Cache.get()} which handles:
     * <ul>
     *   <li>L1 HIT: returns immediately (~50ns)</li>
     *   <li>L1 MISS: calls loader (L2 -> DB), coalesces concurrent requests</li>
     * </ul>
     *
     * @param key cache key to look up
     * @return the value, or {@code null} if not found in any tier
     */
    @Override
    public String get(String key) {
        String l1Key = PATTERN + ":" + key;
        String value = caffeineL1Cache.get(l1Key);
        if (value != null) {
            incrementMeta("hits");
            log.info("READ-THROUGH read: key={} (Caffeine managed)", key);
        } else {
            incrementMeta("misses");
        }
        return value;
    }

    @Override
    public CacheStateResponse getState() {
        CachingPatternStateResponse state = buildState();
        return CacheStateResponse.builder()
                .policy("READ-THROUGH")
                .capacity(-1)
                .currentSize(state.getCacheSize())
                .entries(state.getCacheEntries())
                .evictionOrder("Caffeine LoadingCache(stampede-safe)->L2(Redis)->DB")
                .build();
    }

    @Override
    public CachingPatternStateResponse buildState() {
        Map<Object, Object> l2Entries = redisTemplate.opsForHash().entries(L2_KEY);
        Map<Object, Object> dbEntries = database.readAll(PATTERN);
        Map<Object, Object> meta = redisTemplate.opsForHash().entries(META_KEY);

        List<CacheEntry> cacheList = new ArrayList<>();
        l2Entries.forEach((k, v) -> cacheList.add(
                CacheEntry.builder().key(k.toString()).value(v.toString()).build()));

        Map<String, String> dbMap = new LinkedHashMap<>();
        dbEntries.forEach((k, v) -> dbMap.put(k.toString(), v.toString()));

        Map<String, Object> metaMap = new HashMap<>();
        meta.forEach((k, v) -> metaMap.put(k.toString(), v));
        metaMap.put("l1-estimated-size", caffeineL1Cache.estimatedSize());
        metaMap.put("l1-stats", caffeineL1Cache.stats().toString());

        return CachingPatternStateResponse.builder()
                .pattern("Read-Through (Caffeine LoadingCache)")
                .description("Caffeine LoadingCache handles miss + stampede protection. "
                        + "Only one thread loads per key, others wait.")
                .cacheSize(l2Entries.size())
                .dbSize(dbEntries.size())
                .cacheEntries(cacheList)
                .dbEntries(dbMap)
                .metadata(metaMap)
                .asciiDiagram(
                        "READ: App->Caffeine.get()->[HIT]->return | "
                        + "[MISS]->loader(L2->DB, coalesced)->return\n"
                        + "WRITE: App->DB->DEL L2->invalidate L1")
                .build();
    }

    @Override
    public void clear() {
        redisTemplate.delete(L2_KEY);
        redisTemplate.delete(META_KEY);
        caffeineL1Cache.invalidateAll();
    }

    @Override
    public String getFromDb(String key) {
        return database.read(PATTERN, key);
    }

    @Override
    public Map<String, String> getDbState() {
        Map<String, String> result = new LinkedHashMap<>();
        database.readAll(PATTERN).forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    @Override
    public void seedDb(Map<String, String> entries) {
        database.seed(PATTERN, entries);
    }

    private void incrementMeta(String field) {
        redisTemplate.opsForHash().increment(META_KEY, field, 1);
    }
}
