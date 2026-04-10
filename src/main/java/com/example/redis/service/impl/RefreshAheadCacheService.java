package com.example.redis.service.impl;

import com.example.redis.dto.CacheEntry;
import com.example.redis.dto.CacheStateResponse;
import com.example.redis.dto.CachingPatternStateResponse;
import com.example.redis.service.CachingPatternService;
import com.github.benmanes.caffeine.cache.Cache;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Refresh-Ahead Cache Service                               |
 * +------------------------------------------------------------+
 * |  Proactive refresh pattern that keeps hot keys fresh by    |
 * |  triggering an asynchronous background reload when a       |
 * |  cached entry enters its "refresh zone" (last N% of TTL). |
 * |  The stale value is returned immediately while the         |
 * |  refresh happens in the background -- hot keys never       |
 * |  experience miss latency.                                  |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Read flow with refresh-zone check:
 * <pre>
 *  +---------+  GET   +-------------+  MISS  +------------+
 *  |   App   |-------&gt;| L1 Caffeine |-------&gt;| L2 Redis   |
 *  +---------+        +------+------+        +------+-----+
 *       ^              HIT   |                      |
 *       +---- return -------+             +---------+---------+
 *       |                                 | age &lt; 80% TTL    |
 *       |                                 | ---&gt; return       |
 *       |                                 |                   |
 *       |                                 | age &gt;= 80% TTL   |
 *       |                                 | ---&gt; return +     |
 *       |                                 |   async refresh   |
 *       |                                 |                   |
 *       |                                 | MISS (expired)    |
 *       |                                 | ---&gt; DB load      |
 *       |                                 +-------------------+
 *       |
 *       +--- populate L1 + L2 + TTL timestamp
 * </pre>
 *
 * <p>Async refresh flow:
 * <pre>
 *  +----------+  submit  +----------+  SELECT  +------------+
 *  | Executor |&lt;---------|  Trigger |&lt;---------| PostgreSQL |
 *  +----------+          +----------+          +------+-----+
 *                                                     |
 *                                               +-----v------+
 *                                               | L2 + L1 +  |
 *                                               | TTL update  |
 *                                               +-------------+
 * </pre>
 *
 * @see CachingPatternService
 */
@Slf4j
@Service("refreshAheadService")
public class RefreshAheadCacheService implements CachingPatternService {

    private static final String L2_KEY = "pattern:refresh-ahead:cache";
    private static final String TTL_KEY = "pattern:refresh-ahead:ttl";
    private static final String META_KEY = "pattern:refresh-ahead:meta";
    private static final String PATTERN = "refresh-ahead";

    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseService database;
    private final ExecutorService executor;
    private final long ttlSeconds;
    private final int thresholdPercent;
    private final Cache<String, String> l1Cache;
    private final ConcurrentHashMap<String, Boolean> refreshInFlight = new ConcurrentHashMap<>();

    public RefreshAheadCacheService(
            RedisTemplate<String, String> redisTemplate,
            DatabaseService database,
            @Qualifier("cachingPatternExecutor") ExecutorService executor,
            @Value("${app.caching.refresh-ahead.ttl-seconds:60}") long ttlSeconds,
            @Value("${app.caching.refresh-ahead.threshold-percent:80}") int thresholdPercent,
            @Qualifier("caffeineL1Cache") Cache<String, String> l1Cache) {
        this.redisTemplate = redisTemplate;
        this.database = database;
        this.executor = executor;
        this.ttlSeconds = ttlSeconds;
        this.thresholdPercent = thresholdPercent;
        this.l1Cache = l1Cache;
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Refresh-Ahead WRITE                                       |
     * +------------------------------------------------------------+
     * |  Updates DB, L2 (Redis), L1 (Caffeine), and records the   |
     * |  write timestamp so that reads can calculate the entry's   |
     * |  age relative to the configured TTL.                       |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Write flow:
     * <pre>
     *  +---------+  INSERT  +------------+
     *  |   App   |---------&gt;| PostgreSQL |
     *  +---------+          +------+-----+
     *       |                      | OK
     *       +---&gt; SET L2 key       |
     *       +---&gt; put L1 key       |
     *       +---&gt; HSET TTL timestamp
     * </pre>
     *
     * @param key   cache key to write
     * @param value value to persist across all tiers
     */
    @Override
    public void put(String key, String value) {
        database.write(PATTERN, key, value);
        redisTemplate.opsForHash().put(L2_KEY, key, value);
        l1Cache.put(PATTERN + ":" + key, value);
        redisTemplate.opsForHash().put(TTL_KEY, key, String.valueOf(System.currentTimeMillis()));
        log.info("REFRESH-AHEAD write: DB+L2+L1 updated, TTL recorded for key={}", key);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Refresh-Ahead READ                                        |
     * +------------------------------------------------------------+
     * |  Checks L1 first (no TTL check -- short-lived), then L2.  |
     * |  On L2 hit, evaluates the entry age against the TTL:       |
     * |   - age &lt; threshold: normal return + promote to L1         |
     * |   - age &gt;= threshold and &lt; TTL: return + async refresh    |
     * |   - age &gt;= TTL: evict, fall through to DB load            |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Read flow:
     * <pre>
     *  +---------+  GET   +-------------+
     *  |   App   |-------&gt;| L1 Caffeine |
     *  +---------+        +------+------+
     *       ^              HIT   |  MISS
     *       +---- return -------+   |
     *       |                 +-----v------+
     *       |                 | L2 Redis   |
     *       |                 +--+----+----+
     *       |             HIT    |    | MISS
     *       |          +---------+    +--------+
     *       |          | check age             |
     *       |          +-----+-----+     +-----v------+
     *       |    fresh |     | old |     | PostgreSQL  |
     *       |   +------+  +--+----+-+   +------+------+
     *       |   |return|  |return + |          |
     *       |   +------+  |async   |   populate L1+L2+TTL
     *       |             |refresh |
     *       |             +--------+
     *       +--- promote to L1
     * </pre>
     *
     * @param key cache key to look up
     * @return the value (possibly stale during refresh), or {@code null} if not found
     */
    @Override
    public String get(String key) {
        String l1Key = PATTERN + ":" + key;

        String l1Value = l1Cache.getIfPresent(l1Key);
        if (l1Value != null) {
            incrementMeta("l1-hits");
            log.info("REFRESH-AHEAD read: L1 HIT key={}", key);
            return l1Value;
        }

        Object l2Value = redisTemplate.opsForHash().get(L2_KEY, key);
        if (l2Value != null) {
            incrementMeta("l2-hits");
            l1Cache.put(l1Key, l2Value.toString());
            checkAndTriggerRefresh(key);
            return l2Value.toString();
        }

        incrementMeta("db-misses");
        return loadFromDb(key);
    }

    /**
     * Evaluates the entry's age against the configured TTL threshold. If the
     * entry is in the refresh zone (age between threshold% and 100% of TTL),
     * triggers an async refresh. If fully expired, evicts from L2 + L1.
     *
     * @param key the cache key to check
     */
    private void checkAndTriggerRefresh(String key) {
        Object tsObj = redisTemplate.opsForHash().get(TTL_KEY, key);
        if (tsObj == null) {
            return;
        }
        long writeTime = Long.parseLong(tsObj.toString());
        long ageMs = System.currentTimeMillis() - writeTime;
        long ttlMs = ttlSeconds * 1000;
        long thresholdMs = ttlMs * thresholdPercent / 100;

        if (ageMs > thresholdMs && ageMs < ttlMs) {
            triggerAsyncRefresh(key);
        } else if (ageMs >= ttlMs) {
            redisTemplate.opsForHash().delete(L2_KEY, key);
            redisTemplate.opsForHash().delete(TTL_KEY, key);
            l1Cache.invalidate(PATTERN + ":" + key);
            incrementMeta("expired-evictions");
            log.info("REFRESH-AHEAD: key={} expired (age={}ms > ttl={}ms)", key, ageMs, ttlMs);
        }
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Trigger Async Refresh                                     |
     * +------------------------------------------------------------+
     * |  Submits a background task to reload the value from DB     |
     * |  and update L2 + L1 + TTL timestamp. Uses a               |
     * |  {@link ConcurrentHashMap} as an in-flight guard so only  |
     * |  one refresh runs per key at a time.                       |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Async refresh flow:
     * <pre>
     *  +----------+  putIfAbsent  +---------------+
     *  | Trigger  |---------------&gt;| refreshInFlight|
     *  +----------+               +-------+-------+
     *       |                       new?  | duplicate?
     *       |                    +--------+    skip
     *       |                    |
     *  +----v-----+  submit  +---v------+  SELECT  +------------+
     *  | Executor |&lt;---------|  Worker  |&lt;---------| PostgreSQL |
     *  +----------+          +---+------+          +------------+
     *                            |
     *                  update L2 + L1 + TTL
     *                  remove from inFlight
     * </pre>
     *
     * @param key the cache key to refresh in the background
     */
    private void triggerAsyncRefresh(String key) {
        if (refreshInFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        incrementMeta("refresh-triggers");
        log.info("REFRESH-AHEAD: async refresh triggered for key={}", key);
        executor.submit(() -> {
            try {
                String value = database.read(PATTERN, key);
                if (value != null) {
                    redisTemplate.opsForHash().put(L2_KEY, key, value);
                    l1Cache.put(PATTERN + ":" + key, value);
                    redisTemplate.opsForHash().put(TTL_KEY, key,
                            String.valueOf(System.currentTimeMillis()));
                }
            } finally {
                refreshInFlight.remove(key);
            }
        });
    }

    /**
     * Loads a value directly from DB and populates L2, L1, and the TTL
     * timestamp hash.
     *
     * @param key the cache key to load
     * @return the value, or {@code null} if not found in DB
     */
    private String loadFromDb(String key) {
        String value = database.read(PATTERN, key);
        if (value != null) {
            redisTemplate.opsForHash().put(L2_KEY, key, value);
            l1Cache.put(PATTERN + ":" + key, value);
            redisTemplate.opsForHash().put(TTL_KEY, key,
                    String.valueOf(System.currentTimeMillis()));
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
                .policy("REFRESH-AHEAD")
                .capacity(-1)
                .currentSize(state.getCacheSize())
                .entries(state.getCacheEntries())
                .evictionOrder("L1(Caffeine)->L2(Redis)->DB(PostgreSQL)")
                .build();
    }

    /**
     * Builds the full pattern state including L2 cache entries (with age in seconds),
     * DB entries, and metadata (TTL config, threshold percent, refresh window,
     * refresh trigger count, expired evictions, L1 stats).
     *
     * @return complete {@link CachingPatternStateResponse} with all tiers
     */
    @Override
    public CachingPatternStateResponse buildState() {
        Map<Object, Object> l2Entries = redisTemplate.opsForHash().entries(L2_KEY);
        Map<Object, Object> dbEntries = database.readAll(PATTERN);
        Map<Object, Object> meta = redisTemplate.opsForHash().entries(META_KEY);
        Map<Object, Object> ttlEntries = redisTemplate.opsForHash().entries(TTL_KEY);

        List<CacheEntry> cacheList = new ArrayList<>();
        long now = System.currentTimeMillis();
        l2Entries.forEach((k, v) -> {
            Object ts = ttlEntries.get(k);
            Double ageSeconds = ts != null
                    ? (now - Long.parseLong(ts.toString())) / 1000.0 : null;
            cacheList.add(CacheEntry.builder()
                    .key(k.toString())
                    .value(v.toString())
                    .score(ageSeconds)
                    .build());
        });

        Map<String, String> dbMap = new LinkedHashMap<>();
        dbEntries.forEach((k, v) -> dbMap.put(k.toString(), v.toString()));

        Map<String, Object> metaMap = new HashMap<>();
        meta.forEach((k, v) -> metaMap.put(k.toString(), v));
        metaMap.put("ttlSeconds", ttlSeconds);
        metaMap.put("thresholdPercent", thresholdPercent);
        metaMap.put("refreshWindowSeconds", ttlSeconds * (100 - thresholdPercent) / 100);
        metaMap.put("l1-estimated-size", l1Cache.estimatedSize());
        metaMap.put("l1-stats", l1Cache.stats().toString());

        return CachingPatternStateResponse.builder()
                .pattern("Refresh-Ahead")
                .description("Proactively refreshes entries in the last "
                        + (100 - thresholdPercent) + "% of TTL. "
                        + "L1(Caffeine)->L2(Redis)->DB. Hot keys never see miss latency.")
                .cacheSize(l2Entries.size())
                .dbSize(dbEntries.size())
                .cacheEntries(cacheList)
                .dbEntries(dbMap)
                .metadata(metaMap)
                .asciiDiagram(
                        "READ:  App->L1[HIT]->return\n"
                        + "       App->L1[MISS]->L2[HIT, age<80% TTL]->L1->return\n"
                        + "       App->L1[MISS]->L2[HIT, age>80% TTL]->L1->return"
                        + " + async{DB->L2+L1}\n"
                        + "       App->L1[MISS]->L2[MISS]->DB->L2+L1->return\n"
                        + "WRITE: App->DB->L2+L1+TTL timestamp")
                .build();
    }

    /**
     * Clears L2 (Redis hash), TTL hash, metadata counters, and all L1 (Caffeine) entries.
     */
    @Override
    public void clear() {
        redisTemplate.delete(L2_KEY);
        redisTemplate.delete(TTL_KEY);
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
