package com.example.redis.service;

import com.example.redis.dto.CacheStateResponse;

/**
 * <b>Cache Service Interface</b>
 *
 * <p>Base contract for all cache implementations (LRU, LFU, and caching-pattern variants). Defines the minimal CRUD
 * surface every cache must expose: put, get, state inspection, and full clear.
 */
public interface CacheService {

    /**
     * Stores a key-value pair in the cache, potentially evicting an existing entry.
     *
     * @param key   the cache key
     * @param value the value to store
     */
    void put(String key, String value);

    /**
     * Retrieves the value associated with the given key.
     *
     * @param key the cache key
     * @return the cached value, or {@code null} if not present
     */
    String get(String key);

    /**
     * Returns a snapshot of the current cache state including all entries and metadata.
     *
     * @return a {@link CacheStateResponse} describing the cache contents
     */
    CacheStateResponse getState();

    /**
     * Removes all entries from the cache.
     */
    void clear();
}
