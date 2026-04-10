package com.example.redis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <b>Cache State Response</b>
 *
 * <p>Snapshot of the current state for LRU/LFU eviction-based caches. Includes the eviction policy in effect, the
 * maximum capacity, the current number of entries, the entries themselves, and a description of the eviction ordering
 * so clients can visualize which entry will be evicted next.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheStateResponse {

    private String policy;
    private int capacity;
    private long currentSize;
    private List<CacheEntry> entries;
    private String evictionOrder;
}
