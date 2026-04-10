package com.example.redis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <b>Cache Entry</b>
 *
 * <p>Represents a single cache entry with a key, value, and an optional score. The score is populated when entries
 * originate from a Redis sorted set (e.g., eviction-ordered caches) and is {@code null} for plain key-value caching
 * patterns.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheEntry {

    private String key;
    private String value;
    private Double score;
}
