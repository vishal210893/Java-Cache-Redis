package com.example.redis.service.impl;

import com.example.redis.dto.CacheEntry;
import com.example.redis.dto.CacheStateResponse;
import com.example.redis.dto.CachingPatternStateResponse;
import com.example.redis.service.CachingPatternService;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Write-Back (Write-Behind) Cache Service                   |
 * +------------------------------------------------------------+
 * |  Asynchronous write-back pattern with dirty key tracking.  |
 * |  Writes go only to L1 (Caffeine) + L2 (Redis) and mark    |
 * |  the key as dirty. A background worker periodically        |
 * |  flushes dirty keys to PostgreSQL in batch.                |
 * |                                                            |
 * |  Trade-offs:                                               |
 * |   - Ultra-fast writes (~1ms vs ~5-20ms for DB)             |
 * |   - Write coalescing: N writes to same key = 1 DB write   |
 * |   - Risk: data loss if cache crashes before flush          |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Write flow (fast path):
 * <pre>
 *  +---------+  SET   +----------+  put   +-----------+  SADD  +----------+
 *  |   App   |-------&gt;| L2 Redis |-------&gt;| L1 Cache  |-------&gt;| DirtySet |
 *  +---------+        +----------+        +-----------+        +----------+
 *       ^                                                           |
 *       +-------------- confirm (~1ms total) -----------------------+
 * </pre>
 *
 * <p>Background flush flow:
 * <pre>
 *  +----------+  SMEMBERS  +----------+  MGET   +----------+  batch INSERT  +------------+
 *  |  Worker  |-----------&gt;| DirtySet |--------&gt;| L2 Redis |---------------&gt;| PostgreSQL |
 *  +----------+            +-----+----+         +----------+               +------------+
 *                                |
 *                           DEL DirtySet
 * </pre>
 *
 * @see CachingPatternService
 */
@Slf4j
@Service("writeBackService")
public class WriteBackCacheService implements CachingPatternService {

    private static final String L2_KEY = "pattern:write-back:cache";
    private static final String DIRTY_KEY = "pattern:write-back:dirty";
    private static final String META_KEY = "pattern:write-back:meta";
    private static final String PATTERN = "write-back";

    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseService database;
    private final ExecutorService executor;
    private final long flushIntervalSeconds;
    private final Cache<String, String> l1Cache;

    public WriteBackCacheService(
            RedisTemplate<String, String> redisTemplate,
            DatabaseService database,
            @Qualifier("cachingPatternExecutor") ExecutorService executor,
            @Value("${app.caching.write-back.flush-interval-seconds:10}") long flushIntervalSeconds,
            @Qualifier("caffeineL1Cache") Cache<String, String> l1Cache) {
        this.redisTemplate = redisTemplate;
        this.database = database;
        this.executor = executor;
        this.flushIntervalSeconds = flushIntervalSeconds;
        this.l1Cache = l1Cache;
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Start Background Flush                                    |
     * +------------------------------------------------------------+
     * |  Launches a daemon loop that calls {@link #flush()} every  |
     * |  N seconds (configurable via                               |
     * |  {@code app.caching.write-back.flush-interval-seconds}).   |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Background loop:
     * <pre>
     *  +--------+  sleep(N)  +-------+  flush()  +------------+
     *  | Worker |-----------&gt;| Wake  |----------&gt;| DirtySet   |
     *  +--------+            +-------+           | ---&gt; DB    |
     *       ^                                    +-----+------+
     *       +---------------- loop ----------------------+
     * </pre>
     */
    @PostConstruct
    public void startBackgroundFlush() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.SECONDS.sleep(flushIntervalSeconds);
                    flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        log.info("WRITE-BACK: background flush started (interval={}s)", flushIntervalSeconds);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Write-Back WRITE (async DB)                               |
     * +------------------------------------------------------------+
     * |  Writes to L2 (Redis) + L1 (Caffeine) + marks the key     |
     * |  dirty. The DB is NOT updated here -- it happens later     |
     * |  during the background flush cycle.                        |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Write flow:
     * <pre>
     *  +---------+  SET key  +----------+
     *  |   App   |----------&gt;| L2 Redis |
     *  +---------+           +----------+
     *       |
     *       +---&gt; put L1 (Caffeine)
     *       |
     *       +---&gt; SADD key to DirtySet
     *       |
     *       +---&gt; confirm (~1ms total)
     * </pre>
     *
     * @param key   cache key to write
     * @param value value to cache (DB update deferred)
     */
    @Override
    public void put(String key, String value) {
        redisTemplate.opsForHash().put(L2_KEY, key, value);
        l1Cache.put(PATTERN + ":" + key, value);
        redisTemplate.opsForSet().add(DIRTY_KEY, key);
        incrementMeta("writes");
        log.info("WRITE-BACK write: L1+L2 cached + marked dirty key={}", key);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Write-Back READ                                           |
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
            log.info("WRITE-BACK read: L1 HIT key={}", key);
            return l1Value;
        }

        Object l2Value = redisTemplate.opsForHash().get(L2_KEY, key);
        if (l2Value != null) {
            incrementMeta("l2-hits");
            l1Cache.put(l1Key, l2Value.toString());
            log.info("WRITE-BACK read: L2 HIT key={}, promoted to L1", key);
            return l2Value.toString();
        }

        incrementMeta("db-misses");
        String dbValue = database.read(PATTERN, key);
        if (dbValue != null) {
            redisTemplate.opsForHash().put(L2_KEY, key, dbValue);
            l1Cache.put(l1Key, dbValue);
            log.info("WRITE-BACK read: DB fetch key={}, cached in L1+L2", key);
        }
        return dbValue;
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Flush Dirty Keys                                          |
     * +------------------------------------------------------------+
     * |  Reads all members from the DirtySet, fetches their        |
     * |  current values from L2 (Redis), and batch-writes them     |
     * |  to PostgreSQL. Clears each key from DirtySet after        |
     * |  successful persist.                                       |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Flush flow:
     * <pre>
     *  +--------+  SMEMBERS  +----------+
     *  | Flush  |-----------&gt;| DirtySet |
     *  +--------+            +----+-----+
     *                             |  for each key
     *                       +-----v------+
     *                       | HGET from  |
     *                       | L2 Redis   |
     *                       +-----+------+
     *                             |
     *                       +-----v------+
     *                       | INSERT to  |
     *                       | PostgreSQL |
     *                       +-----+------+
     *                             |
     *                       +-----v------+
     *                       | SREM from  |
     *                       | DirtySet   |
     *                       +------------+
     * </pre>
     */
    @Override
    public void flush() {
        Set<String> dirtyKeys = redisTemplate.opsForSet().members(DIRTY_KEY);
        if (dirtyKeys == null || dirtyKeys.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        int count = 0;
        for (String key : dirtyKeys) {
            Object value = redisTemplate.opsForHash().get(L2_KEY, key);
            if (value != null) {
                database.write(PATTERN, key, value.toString());
                count++;
            }
            redisTemplate.opsForSet().remove(DIRTY_KEY, key);
        }
        long elapsed = System.currentTimeMillis() - start;
        incrementMeta("flushes");
        incrementMeta("total-flushed-keys", count);
        log.info("WRITE-BACK flush: {} keys flushed to DB in {}ms", count, elapsed);
    }

    /**
     * Flushes all remaining dirty keys to DB on application shutdown to
     * minimize data loss.
     */
    @PreDestroy
    public void shutdownFlush() {
        log.info("WRITE-BACK: flushing dirty keys before shutdown...");
        flush();
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
                .policy("WRITE-BACK")
                .capacity(-1)
                .currentSize(state.getCacheSize())
                .entries(state.getCacheEntries())
                .evictionOrder("L1(Caffeine)->L2(Redis)->async flush->DB(PostgreSQL)")
                .build();
    }

    /**
     * Builds the full pattern state including L2 cache entries, DB entries, dirty key set,
     * and metadata (write count, flush count, total flushed keys, flush interval, L1 stats).
     *
     * @return complete {@link CachingPatternStateResponse} with all tiers
     */
    @Override
    public CachingPatternStateResponse buildState() {
        Map<Object, Object> l2Entries = redisTemplate.opsForHash().entries(L2_KEY);
        Map<Object, Object> dbEntries = database.readAll(PATTERN);
        Map<Object, Object> meta = redisTemplate.opsForHash().entries(META_KEY);
        Set<String> dirtyKeys = redisTemplate.opsForSet().members(DIRTY_KEY);

        List<CacheEntry> cacheList = new ArrayList<>();
        l2Entries.forEach((k, v) -> cacheList.add(
                CacheEntry.builder().key(k.toString()).value(v.toString()).build()));

        Map<String, String> dbMap = new LinkedHashMap<>();
        dbEntries.forEach((k, v) -> dbMap.put(k.toString(), v.toString()));

        Map<String, Object> metaMap = new HashMap<>();
        meta.forEach((k, v) -> metaMap.put(k.toString(), v));
        metaMap.put("dirtyKeys", dirtyKeys);
        metaMap.put("flushIntervalSeconds", flushIntervalSeconds);
        metaMap.put("l1-estimated-size", l1Cache.estimatedSize());
        metaMap.put("l1-stats", l1Cache.stats().toString());

        return CachingPatternStateResponse.builder()
                .pattern("Write-Back (Write-Behind)")
                .description("Writes to L1+L2 only (~1ms). "
                        + "Background flush to DB. Risk: data loss before flush.")
                .cacheSize(l2Entries.size())
                .dbSize(dbEntries.size())
                .cacheEntries(cacheList)
                .dbEntries(dbMap)
                .metadata(metaMap)
                .asciiDiagram(
                        "WRITE: App->L2(Redis)+L1(Caffeine)+markDirty->return\n"
                        + "FLUSH: Worker->DirtySet->L2(MGET)->DB(batch)->clear DirtySet\n"
                        + "READ:  App->L1[HIT]->return | L1[MISS]->L2[HIT]->L1->return"
                        + " | L2[MISS]->DB->L2+L1->return")
                .build();
    }

    /**
     * Flushes dirty keys first, then clears L2 (Redis hash), dirty set,
     * metadata counters, and all L1 (Caffeine) entries.
     */
    @Override
    public void clear() {
        flush();
        redisTemplate.delete(L2_KEY);
        redisTemplate.delete(DIRTY_KEY);
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
