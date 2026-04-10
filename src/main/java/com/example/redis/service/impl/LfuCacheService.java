package com.example.redis.service.impl;

import com.example.redis.dto.CacheEntry;
import com.example.redis.dto.CacheStateResponse;
import com.example.redis.service.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  LFU (Least Frequently Used) Cache Service                 |
 * +------------------------------------------------------------+
 * |  Eviction-based cache backed by Redis using two data       |
 * |  structures: a HASH for key-value storage and a ZSET for   |
 * |  tracking access frequency via scores.                     |
 * |                                                            |
 * |  Eviction targets the member with the lowest score         |
 * |  (least frequent) via {@code ZRANGE 0 0}. Each access      |
 * |  increments the score with {@code ZINCRBY}.                |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Redis layout:
 * <pre>
 *  +-------------------------------+    +-----------------------------------+
 *  | HASH  cache:lfu:data          |    | ZSET  cache:lfu:frequency         |
 *  |  { k1:v1, k2:v2, ... }       |    |  { k1:3, k2:7, ... }             |
 *  +-------------------------------+    |    ^^ lowest freq = evict first  |
 *                                       +-----------------------------------+
 * </pre>
 *
 * <p>Eviction flow:
 * <pre>
 *  +----------+  ZCARD   +-------+  full?  +----------+  ZRANGE 0 0  +----------+
 *  |  put()   |----------&gt;| ZSET |--------&gt;| Capacity |-------------&gt;| Evict    |
 *  +----------+          +-------+  yes    | Check    |              | least    |
 *                                          +----------+              | frequent |
 *                                                                    +----------+
 * </pre>
 *
 * @see CacheService
 */
@Slf4j
@Service("lfuCacheService")
public class LfuCacheService implements CacheService {

    private static final String ZSET_KEY = "cache:lfu:frequency";
    private static final String HASH_KEY = "cache:lfu:data";

    private final RedisTemplate<String, String> redisTemplate;
    private final int capacity;

    public LfuCacheService(RedisTemplate<String, String> redisTemplate,
                           @Value("${app.cache.lfu-capacity:5}") int capacity) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  LFU PUT                                                   |
     * +------------------------------------------------------------+
     * |  Inserts or updates a key-value pair. If the cache is at   |
     * |  capacity and the key is new, evicts the least frequently  |
     * |  used entry (lowest score in the ZSET). Existing keys get  |
     * |  their frequency incremented; new keys start at 1.         |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Put flow:
     * <pre>
     *  +---------+  ZCARD  +------+
     *  |   App   |--------&gt;| ZSET |
     *  +---------+         +--+---+
     *       |                 | size &gt;= capacity and new key?
     *       |           +-----v------+
     *       |           | ZRANGE 0 0 |---&gt; find least frequent
     *       |           +-----+------+
     *       |                 |
     *       |           +-----v------+
     *       |           | ZREM + HDEL|---&gt; evict
     *       |           +------------+
     *       |
     *       +---&gt; existing key?
     *       |       YES: ZINCRBY +1 ---&gt; bump frequency
     *       |       NO:  ZADD 1     ---&gt; initial frequency
     *       |
     *       +---&gt; HSET ---&gt; HASH    (store value)
     * </pre>
     *
     * @param key   cache key
     * @param value value to cache
     */
    @Override
    public void put(String key, String value) {
        Long currentSize = redisTemplate.opsForZSet().size(ZSET_KEY);
        boolean exists = redisTemplate.opsForZSet().score(ZSET_KEY, key) != null;

        if (!exists && currentSize != null && currentSize >= capacity) {
            Set<String> leastFrequent = redisTemplate.opsForZSet().range(ZSET_KEY, 0, 0);
            if (leastFrequent != null && !leastFrequent.isEmpty()) {
                String evictedKey = leastFrequent.iterator().next();
                Double evictedFreq = redisTemplate.opsForZSet().score(ZSET_KEY, evictedKey);
                redisTemplate.opsForZSet().remove(ZSET_KEY, evictedKey);
                redisTemplate.opsForHash().delete(HASH_KEY, evictedKey);
                log.info("LFU evicted key={} (frequency={})", evictedKey, evictedFreq != null ? evictedFreq.longValue() : "unknown");
            }
        }

        if (exists) {
            redisTemplate.opsForZSet().incrementScore(ZSET_KEY, key, 1);
            log.info("LFU updated key={}, incremented frequency", key);
        } else {
            redisTemplate.opsForZSet().add(ZSET_KEY, key, 1);
            log.info("LFU added key={} with initial frequency=1", key);
        }

        redisTemplate.opsForHash().put(HASH_KEY, key, value);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  LFU GET                                                   |
     * +------------------------------------------------------------+
     * |  Fetches the value from the HASH. On hit, increments the   |
     * |  frequency score in the ZSET via {@code ZINCRBY +1} so     |
     * |  frequently accessed keys are protected from eviction.     |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Get flow:
     * <pre>
     *  +---------+  HGET  +------+
     *  |   App   |-------&gt;| HASH |
     *  +---------+        +--+---+
     *       ^           MISS |  HIT
     *       |    null &lt;------+   |
     *       |                +---v--------+
     *       |                | ZINCRBY +1 |---&gt; bump frequency
     *       |                +---+--------+
     *       +---- return value --+
     * </pre>
     *
     * @param key cache key
     * @return cached value or {@code null} on miss
     */
    @Override
    public String get(String key) {
        Object value = redisTemplate.opsForHash().get(HASH_KEY, key);
        if (value == null) {
            log.info("LFU cache MISS key={}", key);
            return null;
        }

        Double newFreq = redisTemplate.opsForZSet().incrementScore(ZSET_KEY, key, 1);
        log.info("LFU cache HIT key={}, frequency now={}", key, newFreq);
        return value.toString();
    }

    /**
     * Returns the current cache state including all entries sorted by frequency
     * (lowest first), capacity, and current size.
     *
     * @return {@link CacheStateResponse} with entries ordered by access frequency
     */
    @Override
    public CacheStateResponse getState() {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().rangeWithScores(ZSET_KEY, 0, -1);

        List<CacheEntry> entries = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Object val = redisTemplate.opsForHash().get(HASH_KEY, tuple.getValue());
                entries.add(CacheEntry.builder()
                        .key(tuple.getValue())
                        .value(val != null ? val.toString() : null)
                        .score(tuple.getScore())
                        .build());
            }
        }

        Long size = redisTemplate.opsForZSet().size(ZSET_KEY);
        return CacheStateResponse.builder()
                .policy("LFU")
                .capacity(capacity)
                .currentSize(size != null ? size : 0)
                .entries(entries)
                .evictionOrder("Lowest frequency evicted first — ZRANGE index 0 (ZINCRBY on access)")
                .build();
    }

    /**
     * Clears both the ZSET (frequency tracking) and HASH (data) from Redis.
     */
    @Override
    public void clear() {
        redisTemplate.delete(ZSET_KEY);
        redisTemplate.delete(HASH_KEY);
        log.info("LFU cache cleared");
    }
}
