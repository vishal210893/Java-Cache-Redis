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
 * |  Write-Around Cache Service                                |
 * +------------------------------------------------------------+
 * |  Writes go directly to PostgreSQL, bypassing both L1       |
 * |  (Caffeine) and L2 (Redis) caches entirely. Existing       |
 * |  cache entries for the key are invalidated. Reads populate  |
 * |  the cache on miss via the standard L1 -&gt; L2 -&gt; DB chain. |
 * |                                                            |
 * |  Best for write-heavy, read-light workloads (logs, audit   |
 * |  trails, batch results) where written data may never be    |
 * |  read -- avoids polluting the cache.                       |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Write flow (cache bypass):
 * <pre>
 *  +---------+  INSERT/UPDATE  +------------+
 *  |   App   |-----------------&gt;| PostgreSQL |
 *  +---------+                 +------+-----+
 *       |                             | OK
 *       +---&gt; DEL L2 key             |
 *       +---&gt; invalidate L1          |
 *       +---&gt; return OK              |
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
@Service("writeAroundService")
public class WriteAroundCacheService implements CachingPatternService {

    private static final String L2_KEY = "pattern:write-around:cache";
    private static final String META_KEY = "pattern:write-around:meta";
    private static final String PATTERN = "write-around";

    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseService database;
    private final Cache<String, String> caffeineL1Cache;

    public WriteAroundCacheService(RedisTemplate<String, String> redisTemplate,
                                   DatabaseService database,
                                   @Qualifier("caffeineL1Cache") Cache<String, String> caffeineL1Cache) {
        this.redisTemplate = redisTemplate;
        this.database = database;
        this.caffeineL1Cache = caffeineL1Cache;
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Write-Around WRITE                                        |
     * +------------------------------------------------------------+
     * |  Persists directly to DB, then invalidates L2 and L1.      |
     * |  The cache is NOT populated on write -- the first read     |
     * |  after a write will always miss the cache and fetch from   |
     * |  DB.                                                       |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Write flow:
     * <pre>
     *  +---------+  INSERT/UPDATE  +------------+
     *  |   App   |-----------------&gt;| PostgreSQL |  (~5-20ms)
     *  +---------+                 +------+-----+
     *       |                             | OK
     *       v                             v
     *  +---------+  DEL key  +-----------+
     *  |   App   |-----------&gt;| L2 Redis |
     *  +---------+           +-----------+
     *       |
     *       +---&gt; invalidate L1 (Caffeine)
     * </pre>
     *
     * @param key   cache key to write
     * @param value value to persist (cache NOT populated)
     */
    @Override
    public void put(String key, String value) {
        database.write(PATTERN, key, value);
        redisTemplate.opsForHash().delete(L2_KEY, key);
        caffeineL1Cache.invalidate(PATTERN + ":" + key);
        incrementMeta("writes");
        log.info("WRITE-AROUND write: DB updated, L1+L2 invalidated key={}", key);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Write-Around READ                                         |
     * +------------------------------------------------------------+
     * |  Checks L1 (Caffeine, ~50ns), then L2 (Redis, ~1ms),      |
     * |  then DB (PostgreSQL, ~5-20ms). First read after a write   |
     * |  always hits the DB because the cache was not populated    |
     * |  on write.                                                 |
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

        String l1Value = caffeineL1Cache.getIfPresent(l1Key);
        if (l1Value != null) {
            incrementMeta("l1-hits");
            log.info("WRITE-AROUND read: L1 HIT key={}", key);
            return l1Value;
        }

        Object l2Value = redisTemplate.opsForHash().get(L2_KEY, key);
        if (l2Value != null) {
            incrementMeta("l2-hits");
            caffeineL1Cache.put(l1Key, l2Value.toString());
            log.info("WRITE-AROUND read: L2 HIT key={}, promoted to L1", key);
            return l2Value.toString();
        }

        incrementMeta("db-misses");
        String dbValue = database.read(PATTERN, key);
        if (dbValue != null) {
            redisTemplate.opsForHash().put(L2_KEY, key, dbValue);
            caffeineL1Cache.put(l1Key, dbValue);
            log.info("WRITE-AROUND read: DB fetch key={}, cached in L1+L2", key);
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
        CachingPatternStateResponse response = buildState();
        return CacheStateResponse.builder()
                .policy("WRITE-AROUND")
                .capacity(-1)
                .currentSize(response.getCacheSize())
                .entries(response.getCacheEntries())
                .evictionOrder("L1(Caffeine)->L2(Redis)->DB(PostgreSQL)")
                .build();
    }

    /**
     * Builds the full pattern state including L2 cache entries, DB entries, and
     * metadata (write count, L1/L2 hit counts, DB miss count, L1 stats).
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
        metaMap.put("l1-estimated-size", caffeineL1Cache.estimatedSize());
        metaMap.put("l1-stats", caffeineL1Cache.stats().toString());

        return CachingPatternStateResponse.builder()
                .pattern("Write-Around")
                .description("Writes go to DB only, bypassing L1+L2 cache. "
                        + "Reads populate L1(Caffeine)->L2(Redis) on miss. "
                        + "Best for write-heavy, read-light workloads.")
                .cacheSize(l2Entries.size())
                .dbSize(dbEntries.size())
                .cacheEntries(cacheList)
                .dbEntries(dbMap)
                .metadata(metaMap)
                .asciiDiagram(
                        "READ:  App->L1[HIT]->return | L1[MISS]->L2[HIT]->L1->return"
                        + " | L2[MISS]->DB->L2+L1->return\n"
                        + "WRITE: App->DB->DEL L2->invalidate L1->return")
                .build();
    }

    /**
     * Clears L2 (Redis hash), metadata counters, and all L1 (Caffeine) entries.
     */
    @Override
    public void clear() {
        redisTemplate.delete(L2_KEY);
        redisTemplate.delete(META_KEY);
        caffeineL1Cache.invalidateAll();
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
}
