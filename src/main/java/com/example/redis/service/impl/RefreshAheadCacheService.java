package com.example.redis.service.impl;

import com.example.redis.dto.CacheEntry;
import com.example.redis.dto.CacheStateResponse;
import com.example.redis.dto.CachingPatternStateResponse;
import com.example.redis.service.CachingPatternService;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <b>Refresh-Ahead Cache Service</b>
 *
 * <p>Uses Caffeine's {@link LoadingCache} with {@code refreshAfterWrite()} to automatically
 * trigger async background reloads when entries become stale. This replaces manual TTL tracking
 * -- Caffeine handles the refresh zone, deduplication, and async execution natively.
 *
 * <p>Read flow:
 * <pre>
 *  +---------+  GET   +---------------------------+  MISS/REFRESH  +----------+
 *  |   App   |-------&gt;| Caffeine LoadingCache     |---------------&gt;| L2 Redis |
 *  +---------+        | (auto-refresh after 80%   |                +----+-----+
 *       ^             |  of expiry window)         |                MISS |
 *       |             +---------------------------+               +-----v------+
 *       +---- return (stale value during refresh)                 | PostgreSQL |
 *                                                                 +------------+
 * </pre>
 *
 * @see CachingPatternService
 * @see LoadingCache
 */
@Slf4j
@Service("refreshAheadService")
public class RefreshAheadCacheService implements CachingPatternService {

    private static final String L2_KEY = "pattern:refresh-ahead:cache";
    private static final String META_KEY = "pattern:refresh-ahead:meta";
    private static final String PATTERN = "refresh-ahead";

    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseService database;
    private final ExecutorService executor;
    private final long ttlSeconds;
    private final int thresholdPercent;
    private LoadingCache<String, String> caffeineL1Cache;

    public RefreshAheadCacheService(
            RedisTemplate<String, String> redisTemplate,
            DatabaseService database,
            @Qualifier("cachingPatternExecutor") ExecutorService executor,
            @Value("${app.caching.refresh-ahead.ttl-seconds:60}") long ttlSeconds,
            @Value("${app.caching.refresh-ahead.threshold-percent:80}") int thresholdPercent) {
        this.redisTemplate = redisTemplate;
        this.database = database;
        this.executor = executor;
        this.ttlSeconds = ttlSeconds;
        this.thresholdPercent = thresholdPercent;
    }

    /**
     * <b>Initialize Caffeine LoadingCache with refresh-after-write</b>
     *
     * <p>Configures Caffeine with:
     * <ul>
     *   <li>{@code expireAfterWrite} = full TTL (hard expiry)</li>
     *   <li>{@code refreshAfterWrite} = TTL * threshold% (async refresh zone)</li>
     *   <li>Custom async executor for background reloads</li>
     *   <li>Loader: checks L2 (Redis) first, falls back to DB</li>
     * </ul>
     */
    @PostConstruct
    public void initCache() {
        long refreshSeconds = ttlSeconds * thresholdPercent / 100;
        this.caffeineL1Cache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .refreshAfterWrite(refreshSeconds, TimeUnit.SECONDS)
                .executor(executor)
                .recordStats()
                .build(this::loadForCaffeine);
        log.info("REFRESH-AHEAD: Caffeine LoadingCache initialized "
                + "(expire={}s, refresh={}s)", ttlSeconds, refreshSeconds);
    }

    /**
     * <b>Caffeine CacheLoader</b>
     *
     * <p>Called by Caffeine on miss or refresh. Checks L2 (Redis) first,
     * then falls back to PostgreSQL. On refresh, this runs async on the
     * executor thread — the caller gets the stale value immediately.
     */
    private String loadForCaffeine(String l1Key) {
        String key = l1Key.startsWith(PATTERN + ":") ? l1Key.substring(PATTERN.length() + 1) : l1Key;
        Object l2Value = redisTemplate.opsForHash().get(L2_KEY, key);
        if (l2Value != null) {
            incrementMeta("l2-hits");
            log.info("REFRESH-AHEAD loader: L2 HIT key={}", key);
            return l2Value.toString();
        }
        incrementMeta("db-loads");
        String dbValue = database.read(PATTERN, key);
        if (dbValue != null) {
            redisTemplate.opsForHash().put(L2_KEY, key, dbValue);
            log.info("REFRESH-AHEAD loader: DB fetch key={}, cached in L2", key);
        } else {
            log.info("REFRESH-AHEAD loader: DB MISS key={}", key);
        }
        return dbValue;
    }

    /**
     * <b>Refresh-Ahead WRITE</b>
     *
     * <p>Persists to DB, updates L2 (Redis), and puts into Caffeine LoadingCache.
     *
     * @param key   cache key
     * @param value value to persist
     */
    @Override
    public void put(String key, String value) {
        database.write(PATTERN, key, value);
        redisTemplate.opsForHash().put(L2_KEY, key, value);
        caffeineL1Cache.put(PATTERN + ":" + key, value);
        log.info("REFRESH-AHEAD write: DB+L2+L1 updated for key={}", key);
    }

    /**
     * <b>Refresh-Ahead READ</b>
     *
     * <p>Delegates to Caffeine's {@code get()} which handles:
     * <ul>
     *   <li>L1 HIT (fresh): returns immediately</li>
     *   <li>L1 HIT (stale, past refreshAfterWrite): returns stale + async reload via loader</li>
     *   <li>L1 MISS: synchronous load via loader (L2 -> DB)</li>
     * </ul>
     *
     * @param key cache key
     * @return value from cache or DB, {@code null} if not found anywhere
     */
    @Override
    public String get(String key) {
        String l1Key = PATTERN + ":" + key;
        String value = caffeineL1Cache.get(l1Key);
        if (value != null) {
            incrementMeta("hits");
            log.info("REFRESH-AHEAD read: key={} (Caffeine managed)", key);
        } else {
            incrementMeta("misses");
        }
        return value;
    }

    @Override
    public CacheStateResponse getState() {
        CachingPatternStateResponse state = buildState();
        return CacheStateResponse.builder()
                .policy("REFRESH-AHEAD")
                .capacity(-1)
                .currentSize(state.getCacheSize())
                .entries(state.getCacheEntries())
                .evictionOrder("Caffeine LoadingCache(auto-refresh)->L2(Redis)->DB")
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
        metaMap.put("ttlSeconds", ttlSeconds);
        metaMap.put("thresholdPercent", thresholdPercent);
        metaMap.put("refreshAfterWriteSeconds", ttlSeconds * thresholdPercent / 100);
        metaMap.put("l1-estimated-size", caffeineL1Cache.estimatedSize());
        metaMap.put("l1-stats", caffeineL1Cache.stats().toString());

        return CachingPatternStateResponse.builder()
                .pattern("Refresh-Ahead (Caffeine LoadingCache)")
                .description("Uses Caffeine refreshAfterWrite() for automatic async refresh. "
                        + "Expire=" + ttlSeconds + "s, Refresh=" + (ttlSeconds * thresholdPercent / 100) + "s.")
                .cacheSize(l2Entries.size())
                .dbSize(dbEntries.size())
                .cacheEntries(cacheList)
                .dbEntries(dbMap)
                .metadata(metaMap)
                .asciiDiagram(
                        "READ: App->Caffeine.get()->[fresh]->return | "
                                + "[stale]->return+async reload | [miss]->loader(L2->DB)")
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
