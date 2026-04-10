package com.example.redis.service;

import com.example.redis.dto.CachingPatternStateResponse;

import java.util.Map;

/**
 * <b>Caching Pattern Service Interface</b>
 *
 * <p>Extended contract for caching patterns that operate across multiple layers: an in-process L1 cache (Caffeine),
 * a remote L2 cache (Redis), and a persistent backing store (PostgreSQL). Adds database-aware operations on top of
 * {@link CacheService}.
 *
 * <pre>
 *  ┌───────────┐    ┌────────────┐    ┌───────────┐    ┌────────────┐
 *  │  Client   │───>│  L1 Cache  │───>│  L2 Redis │───>│ PostgreSQL │
 *  └───────────┘    └────────────┘    └───────────┘    └────────────┘
 * </pre>
 */
public interface CachingPatternService extends CacheService {

    /**
     * Reads a value directly from the backing database, bypassing all caches.
     *
     * @param key the product key
     * @return the value from the database, or {@code null} if not found
     */
    String getFromDb(String key);

    /**
     * Returns a snapshot of all key-value pairs currently in the database for this pattern.
     *
     * @return map of database entries (key to value)
     */
    Map<String, String> getDbState();

    /**
     * Seeds the backing database with the given entries for testing or demo purposes.
     *
     * @param entries key-value pairs to persist
     */
    void seedDb(Map<String, String> entries);

    /**
     * Builds a rich state response exposing L1, L2, and DB layers with metadata.
     *
     * @return a {@link CachingPatternStateResponse} with full layer visibility
     */
    CachingPatternStateResponse buildState();

    /**
     * Flushes any pending asynchronous writes (e.g., write-back dirty entries).
     * Default implementation is a no-op for patterns that write synchronously.
     */
    default void flush() {
    }
}
