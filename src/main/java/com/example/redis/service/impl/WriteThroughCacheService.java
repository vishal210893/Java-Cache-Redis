package com.example.redis.service.impl;

import com.example.redis.dto.CacheEntry;
import com.example.redis.dto.CacheStateResponse;
import com.example.redis.dto.CachingPatternStateResponse;
import com.example.redis.service.CachingPatternService;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Write-Through Cache Service                               |
 * +------------------------------------------------------------+
 * |  Synchronous write-through pattern: every write persists   |
 * |  to DB first, then propagates through L2 (Redis) and L1   |
 * |  (Caffeine) in a single synchronous chain. Guarantees      |
 * |  strong read-after-write consistency at the cost of higher |
 * |  write latency (DB + L2 + L1).                            |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Write flow (synchronous chain):
 * <pre>
 *  +---------+  PUT(k,v)  +------------+   OK    +----------+  SET   +-----------+
 *  |   App   |-----------&gt;| PostgreSQL |--------&gt;| L2 Redis |------&gt;| L1 Cache  |
 *  +---------+            +------------+         +----------+       +-----------+
 *       ^                                                                |
 *       +------------------------ confirm --------------------------------+
 * </pre>
 *
 * <p>Read flow:
 * <pre>
 *  +---------+  GET   +-------------+  MISS  +------------+
 *  |   App   |-------&gt;| L1 Caffeine |-------&gt;| L2 Redis   |
 *  +---------+        +------+------+        +------+-----+
 *       ^              HIT   |                MISS  |
 *       +---- return -------+                      |
 *       |                                    +-----v------+
 *       +--- populate L1 + L2 ---------------| PostgreSQL |
 *                                            +------------+
 * </pre>
 *
 * @see CachingPatternService
 */
@Slf4j
@Service("writeThroughService")
public class WriteThroughCacheService implements CachingPatternService {

    private static final String L2_KEY = "pattern:write-through:cache";
    private static final String META_KEY = "pattern:write-through:meta";
    private static final String PATTERN = "write-through";

    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseService database;
    private final Cache<String, String> l1Cache;

    public WriteThroughCacheService(RedisTemplate<String, String> redisTemplate,
                                    DatabaseService database,
                                    @Qualifier("caffeineL1Cache") Cache<String, String> l1Cache) {
        this.redisTemplate = redisTemplate;
        this.database = database;
        this.l1Cache = l1Cache;
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Write-Through WRITE (synchronous)                         |
     * +------------------------------------------------------------+
     * |  Persists to DB, then L2 (Redis), then L1 (Caffeine) in   |
     * |  a single synchronous call. Write latency = DB + L2 + L1. |
     * |  Cache is ALWAYS consistent with DB after a successful     |
     * |  write. If a crash occurs between DB and cache, cache is   |
     * |  stale but self-heals on the next read miss.              |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Synchronous write chain:
     * <pre>
     *  +---------+  INSERT  +------------+
     *  |   App   |---------&gt;| PostgreSQL |  (~5-20ms)
     *  +---------+          +-----+------+
     *                             | OK
     *                       +-----v------+
     *                       | L2 Redis   |  (~1ms)
     *                       +-----+------+
     *                             |
     *                       +-----v------+
     *                       | L1 Caffeine|  (~50ns)
     *                       +-----+------+
     *                             |
     *                        confirm to App
     * </pre>
     *
     * @param key   cache key to write
     * @param value value to persist across all tiers
     */
    @Override
    public void put(String key, String value) {
        long start = System.currentTimeMillis();
        database.write(PATTERN, key, value);
        redisTemplate.opsForHash().put(L2_KEY, key, value);
        l1Cache.put(PATTERN + ":" + key, value);
        long elapsed = System.currentTimeMillis() - start;
        incrementMeta("writes");
        incrementMeta("total-write-latency-ms", elapsed);
        log.info("WRITE-THROUGH write: DB+L2+L1 updated for key={} ({}ms)", key, elapsed);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Write-Through READ                                        |
     * +------------------------------------------------------------+
     * |  Checks L1 (Caffeine, ~50ns), then L2 (Redis, ~1ms),      |
     * |  then DB (PostgreSQL, ~5-20ms). Each hit promotes the      |
     * |  value into upper tiers.                                   |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Read flow:
     * <pre>
     *  +---------+  GET   +-------------+  MISS  +------------+
     *  |   App   |-------&gt;| L1 Caffeine |-------&gt;| L2 Redis   |
     *  +---------+        +------+------+        +------+-----+
     *       ^              HIT   |                MISS  |
     *       +---- return -------+                      |
     *       |                                    +-----v------+
     *       +--- populate L1 + L2 ---------------| PostgreSQL |
     *                                            +------------+
     * </pre>
     *
     * @param key cache key to look up
     * @return the value, or {@code null} if not found in any tier
     */
    @Override
    public String get(String key) {
        String l1Key = PATTERN + ":" + key;

        String l1Value = l1Cache.getIfPresent(l1Key);
        if (l1Value != null) {
            incrementMeta("l1-hits");
            log.info("WRITE-THROUGH read: L1 HIT key={}", key);
            return l1Value;
        }

        Object l2Value = redisTemplate.opsForHash().get(L2_KEY, key);
        if (l2Value != null) {
            incrementMeta("l2-hits");
            l1Cache.put(l1Key, l2Value.toString());
            log.info("WRITE-THROUGH read: L2 HIT key={}, promoted to L1", key);
            return l2Value.toString();
        }

        incrementMeta("db-misses");
        String dbValue = database.read(PATTERN, key);
        if (dbValue != null) {
            redisTemplate.opsForHash().put(L2_KEY, key, dbValue);
            l1Cache.put(l1Key, dbValue);
            log.info("WRITE-THROUGH read: DB fetch key={}, cached in L1+L2", key);
        }
        return dbValue;
    }

    /**
     * Returns a simplified {@link CacheStateResponse} for compatibility with
     * the {@link com.example.redis.service.CacheService} state model.
     *
     * @return current cache state snapshot
     */
    @Override
    public CacheStateResponse getState() {
        CachingPatternStateResponse state = buildState();
        return CacheStateResponse.builder()
                .policy("WRITE-THROUGH")
                .capacity(-1)
                .currentSize(state.getCacheSize())
                .entries(state.getCacheEntries())
                .evictionOrder("L1(Caffeine)->L2(Redis)->DB(PostgreSQL)")
                .build();
    }

    /**
     * Builds the full pattern state including L2 cache entries, DB entries, and
     * metadata (write count, total write latency, L1/L2 hit counts, L1 stats).
     *
     * @return complete {@link CachingPatternStateResponse} with all tiers
     */
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
        metaMap.put("l1-estimated-size", l1Cache.estimatedSize());
        metaMap.put("l1-stats", l1Cache.stats().toString());

        return CachingPatternStateResponse.builder()
                .pattern("Write-Through")
                .description("Writes go to DB first (sync), then L2(Redis), then L1(Caffeine). "
                        + "Strong consistency. Write latency = DB + L2 + L1.")
                .cacheSize(l2Entries.size())
                .dbSize(dbEntries.size())
                .cacheEntries(cacheList)
                .dbEntries(dbMap)
                .metadata(metaMap)
                .asciiDiagram(
                        "READ:  App->L1[HIT]->return | L1[MISS]->L2[HIT]->L1->return"
                        + " | L2[MISS]->DB->L2+L1->return\n"
                        + "WRITE: App->DB(sync)->L2(Redis)->L1(Caffeine)->confirm")
                .build();
    }

    /**
     * Clears L2 (Redis hash), metadata counters, and all L1 (Caffeine) entries.
     */
    @Override
    public void clear() {
        redisTemplate.delete(L2_KEY);
        redisTemplate.delete(META_KEY);
        l1Cache.invalidateAll();
    }

    /**
     * Reads a value directly from the simulated PostgreSQL database, bypassing
     * all cache tiers.
     *
     * @param key the key to look up in the database
     * @return the value, or {@code null} if not found
     */
    @Override
    public String getFromDb(String key) {
        return database.read(PATTERN, key);
    }

    /**
     * Returns all entries currently stored in the simulated database for this pattern.
     *
     * @return ordered map of all database key-value pairs
     */
    @Override
    public Map<String, String> getDbState() {
        Map<String, String> result = new LinkedHashMap<>();
        database.readAll(PATTERN).forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    /**
     * Seeds the simulated database with the given entries, bypassing cache.
     *
     * @param entries key-value pairs to insert into the database
     */
    @Override
    public void seedDb(Map<String, String> entries) {
        database.seed(PATTERN, entries);
    }

    private void incrementMeta(String field) {
        redisTemplate.opsForHash().increment(META_KEY, field, 1);
    }

    private void incrementMeta(String field, long amount) {
        redisTemplate.opsForHash().increment(META_KEY, field, amount);
    }
}
