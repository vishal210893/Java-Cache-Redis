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
 * |  LRU (Least Recently Used) Cache Service                   |
 * +------------------------------------------------------------+
 * |  Eviction-based cache backed by Redis using two data       |
 * |  structures: a HASH for key-value storage and a ZSET for   |
 * |  tracking access recency via timestamps.                   |
 * |                                                            |
 * |  Eviction targets the member with the lowest score         |
 * |  (oldest timestamp) via {@code ZRANGE 0 0}, making it      |
 * |  O(log N) per eviction.                                    |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Redis layout:
 * <pre>
 *  +-------------------------------+    +-----------------------------------+
 *  | HASH  cache:lru:data          |    | ZSET  cache:lru:access            |
 *  |  { k1:v1, k2:v2, ... }       |    |  { k1:ts1, k2:ts2, ... }         |
 *  +-------------------------------+    |    ^^ lowest ts = least recent   |
 *                                       +-----------------------------------+
 * </pre>
 *
 * <p>Eviction flow:
 * <pre>
 *  +----------+  ZCARD   +-------+  full?  +----------+  ZRANGE 0 0  +--------+
 *  |  put()   |----------&gt;| ZSET |--------&gt;| Capacity |-------------&gt;| Evict  |
 *  +----------+          +-------+  yes    | Check    |              | oldest |
 *                                          +----------+              +--------+
 * </pre>
 *
 * @see CacheService
 */
@Slf4j
@Service("lruCacheService")
public class LruCacheService implements CacheService {

    private static final String ZSET_KEY = "cache:lru:access";
    private static final String HASH_KEY = "cache:lru:data";

    private final RedisTemplate<String, String> redisTemplate;
    private final int capacity;

    public LruCacheService(RedisTemplate<String, String> redisTemplate,
                           @Value("${app.cache.lru-capacity:5}") int capacity) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  LRU PUT                                                   |
     * +------------------------------------------------------------+
     * |  Inserts or updates a key-value pair. If the cache is at   |
     * |  capacity and the key is new, evicts the least recently    |
     * |  used entry (lowest timestamp in the ZSET).                |
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
     *       |           | ZRANGE 0 0 |---&gt; find oldest
     *       |           +-----+------+
     *       |                 |
     *       |           +-----v------+
     *       |           | ZREM + HDEL|---&gt; evict
     *       |           +------------+
     *       |
     *       +---&gt; ZADD ts ---&gt; ZSET   (record access timestamp)
     *       +---&gt; HSET    ---&gt; HASH   (store value)
     * </pre>
     *
     * @param key   cache key
     * @param value value to cache
     */
    @Override
    public void put(String key, String value) {
        long timestamp = System.currentTimeMillis();

        Long currentSize = redisTemplate.opsForZSet().size(ZSET_KEY);
        boolean exists = redisTemplate.opsForZSet().score(ZSET_KEY, key) != null;

        if (!exists && currentSize != null && currentSize >= capacity) {
            Set<String> oldest = redisTemplate.opsForZSet().range(ZSET_KEY, 0, 0);
            if (oldest != null && !oldest.isEmpty()) {
                String evictedKey = oldest.iterator().next();
                redisTemplate.opsForZSet().remove(ZSET_KEY, evictedKey);
                redisTemplate.opsForHash().delete(HASH_KEY, evictedKey);
                log.info("LRU evicted key={} (least recently used)", evictedKey);
            }
        }

        redisTemplate.opsForZSet().add(ZSET_KEY, key, timestamp);
        redisTemplate.opsForHash().put(HASH_KEY, key, value);
        log.info("LRU put key={} value={} timestamp={}", key, value, timestamp);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  LRU GET                                                   |
     * +------------------------------------------------------------+
     * |  Fetches the value from the HASH. On hit, refreshes the    |
     * |  access timestamp in the ZSET so the entry moves to the    |
     * |  "most recently used" end.                                 |
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
     *       |                +---v-------+
     *       |                | ZADD ts   |---&gt; refresh timestamp
     *       |                +---+-------+
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
            log.info("LRU cache MISS key={}", key);
            return null;
        }

        long timestamp = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(ZSET_KEY, key, timestamp);
        log.info("LRU cache HIT key={}, updated timestamp={}", key, timestamp);
        return value.toString();
    }

    /**
     * Returns the current cache state including all entries sorted by access
     * time (oldest first), capacity, and current size.
     *
     * @return {@link CacheStateResponse} with entries ordered by recency
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
                .policy("LRU")
                .capacity(capacity)
                .currentSize(size != null ? size : 0)
                .entries(entries)
                .evictionOrder("Lowest timestamp (oldest access) evicted first — ZRANGE index 0")
                .build();
    }

    /**
     * Clears both the ZSET (access tracking) and HASH (data) from Redis.
     */
    @Override
    public void clear() {
        redisTemplate.delete(ZSET_KEY);
        redisTemplate.delete(HASH_KEY);
        log.info("LRU cache cleared");
    }
}
