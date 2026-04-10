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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Read-Through Cache Service                                |
 * +------------------------------------------------------------+
 * |  Cache transparently handles read misses with built-in     |
 * |  stampede protection. On L2 miss, a distributed SETNX      |
 * |  lock ensures only one thread loads from DB per key --     |
 * |  other threads wait and re-check L2 once the loader        |
 * |  finishes.                                                 |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Read flow with stampede protection:
 * <pre>
 *  +---------+  GET  +-------------+  MISS  +------------+
 *  |   App   |------&gt;| L1 Caffeine |-------&gt;| L2 Redis   |
 *  +---------+       +------+------+        +------+-----+
 *       ^             HIT   |                MISS  |
 *       +---- return -------+                      |
 *       |                              +-----------v-----------+
 *       |                              | SETNX lock (5s TTL)   |
 *       |                              +-----+-----+-----------+
 *       |                            acquired|     |not acquired
 *       |                              +-----v---+ +-----v--------+
 *       |                              |   DB    | | wait + retry  |
 *       |                              | SELECT  | | check L2      |
 *       +--- populate L1 + L2 --------+----+----+ +---------------+
 *                                           |
 *                                      unlock key
 * </pre>
 *
 * @see CachingPatternService
 */
@Slf4j
@Service("readThroughService")
public class ReadThroughCacheService implements CachingPatternService {

    private static final String L2_KEY = "pattern:read-through:cache";
    private static final String META_KEY = "pattern:read-through:meta";
    private static final String PATTERN = "read-through";
    private static final String LOCK_PREFIX = "pattern:read-through:lock:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseService database;
    private final Cache<String, String> l1Cache;

    public ReadThroughCacheService(RedisTemplate<String, String> redisTemplate,
                                   DatabaseService database,
                                   @Qualifier("caffeineL1Cache") Cache<String, String> l1Cache) {
        this.redisTemplate = redisTemplate;
        this.database = database;
        this.l1Cache = l1Cache;
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Read-Through WRITE                                        |
     * +------------------------------------------------------------+
     * |  Updates DB and invalidates L1 + L2. Read-through is a     |
     * |  read-side pattern, so writes use cache-aside invalidation.|
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Write flow:
     * <pre>
     *  +---------+  INSERT/UPDATE  +------------+
     *  |   App   |-----------------&gt;| PostgreSQL |
     *  +---------+                 +------------+
     *       |
     *       +---&gt; DEL L2 key ---&gt; invalidate L1
     * </pre>
     *
     * @param key   cache key to write
     * @param value value to persist
     */
    @Override
    public void put(String key, String value) {
        database.write(PATTERN, key, value);
        redisTemplate.opsForHash().delete(L2_KEY, key);
        l1Cache.invalidate(PATTERN + ":" + key);
        log.info("READ-THROUGH write: DB updated, L1+L2 invalidated for key={}", key);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Read-Through READ                                         |
     * +------------------------------------------------------------+
     * |  Checks L1 (Caffeine), then L2 (Redis). On full miss,     |
     * |  acquires a SETNX distributed lock so only ONE thread      |
     * |  loads from DB per key -- preventing cache stampede.       |
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
     *       |                              +-----------v----------+
     *       |                              |  SETNX lock per key  |
     *       |                              +-----------+----------+
     *       |                              acquired    |  blocked
     *       |                              +-----------v--------+
     *       +--- populate L1 + L2 ---------|  DB SELECT + cache |
     *                                      +--------------------+
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
            log.info("READ-THROUGH read: L1 HIT key={}", key);
            return l1Value;
        }

        Object l2Value = redisTemplate.opsForHash().get(L2_KEY, key);
        if (l2Value != null) {
            incrementMeta("l2-hits");
            l1Cache.put(l1Key, l2Value.toString());
            log.info("READ-THROUGH read: L2 HIT key={}, promoted to L1", key);
            return l2Value.toString();
        }

        incrementMeta("db-misses");
        return loadWithStampedeProtection(key);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Stampede Protection                                       |
     * +------------------------------------------------------------+
     * |  Uses Redis SETNX with a 5-second TTL as a distributed    |
     * |  lock. The winning thread loads from DB and caches; all    |
     * |  other threads poll L2 until the value appears.            |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Lock flow:
     * <pre>
     *  +----------+  SETNX  +----------+
     *  | Thread A |--------&gt;| Lock Key |---&gt; acquired: load DB, cache, unlock
     *  +----------+         +----------+
     *  +----------+  SETNX  +----------+
     *  | Thread B |--------&gt;| Lock Key |---&gt; blocked: poll L2 every 50ms
     *  +----------+         +----------+
     * </pre>
     *
     * @param key the cache key experiencing a miss
     * @return loaded value, or {@code null} if not found in DB
     */
    private String loadWithStampedeProtection(String key) {
        String lockKey = LOCK_PREFIX + key;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(5));

        if (Boolean.TRUE.equals(acquired)) {
            return loadFromDbAndCache(key, lockKey);
        }

        incrementMeta("stampede-blocks");
        log.info("READ-THROUGH: stampede blocked for key={}, waiting for loader", key);
        return waitForLoaderAndRetry(key, lockKey);
    }

    /**
     * Loads the value from the database, populates L2 and L1 caches, then
     * releases the distributed lock.
     *
     * @param key     the cache key to load
     * @param lockKey the Redis lock key to release after loading
     * @return the loaded value, or {@code null} if not found in DB
     */
    private String loadFromDbAndCache(String key, String lockKey) {
        try {
            String value = database.read(PATTERN, key);
            if (value != null) {
                redisTemplate.opsForHash().put(L2_KEY, key, value);
                l1Cache.put(PATTERN + ":" + key, value);
            }
            log.info("READ-THROUGH: loaded key={} from DB (loader thread), cached in L1+L2", key);
            return value;
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * Waits for the loader thread to populate L2, polling every 50ms up to 10
     * attempts. Falls back to a direct DB read if the loader finishes without
     * caching the value.
     *
     * @param key     the cache key to wait for
     * @param lockKey the Redis lock key to monitor
     * @return the value once available, or {@code null}
     */
    private String waitForLoaderAndRetry(String key, String lockKey) {
        String l1Key = PATTERN + ":" + key;
        for (int i = 0; i < 10; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String l1Value = l1Cache.getIfPresent(l1Key);
            if (l1Value != null) {
                return l1Value;
            }
            Object l2Value = redisTemplate.opsForHash().get(L2_KEY, key);
            if (l2Value != null) {
                l1Cache.put(l1Key, l2Value.toString());
                return l2Value.toString();
            }
            if (Boolean.FALSE.equals(redisTemplate.hasKey(lockKey))) {
                break;
            }
        }
        String value = database.read(PATTERN, key);
        if (value != null) {
            redisTemplate.opsForHash().put(L2_KEY, key, value);
            l1Cache.put(l1Key, value);
        }
        return value;
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
                .policy("READ-THROUGH")
                .capacity(-1)
                .currentSize(state.getCacheSize())
                .entries(state.getCacheEntries())
                .evictionOrder("L1(Caffeine)->L2(Redis)->DB(PostgreSQL)")
                .build();
    }

    /**
     * Builds the full pattern state including L2 cache entries, DB entries, and
     * metadata (L1/L2 hit counts, DB miss count, stampede-blocks, L1 stats).
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
                .pattern("Read-Through")
                .description("Cache library handles miss internally. L1(Caffeine)->L2(Redis)->DB. "
                        + "Uses SETNX lock for stampede protection — only one thread loads per key.")
                .cacheSize(l2Entries.size())
                .dbSize(dbEntries.size())
                .cacheEntries(cacheList)
                .dbEntries(dbMap)
                .metadata(metaMap)
                .asciiDiagram(
                        "READ:  App->L1[HIT]->return | L1[MISS]->L2[HIT]->L1->return\n"
                        + "       L2[MISS]->SETNX lock->DB->L2+L1->unlock->return\n"
                        + "WRITE: App->DB->DEL L2->invalidate L1")
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
}
