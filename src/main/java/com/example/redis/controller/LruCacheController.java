package com.example.redis.controller;

import com.example.redis.dto.ApiResponse;
import com.example.redis.dto.CachePutRequest;
import com.example.redis.dto.CacheStateResponse;
import com.example.redis.service.CacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  LRU Cache Controller                                      |
 * +------------------------------------------------------------+
 * |  REST controller for the Least Recently Used eviction      |
 * |  cache backed by Redis (HASH + ZSET with timestamp         |
 * |  scores). Evicts the entry with the oldest access time     |
 * |  when capacity is reached.                                 |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Endpoints (base path {@code /api/cache/lru}):
 * <pre>
 *  +---------------------------+--------+---------------------------------+
 *  | Endpoint                  | Method | Description                     |
 *  +---------------------------+--------+---------------------------------+
 *  | /entries                  | POST   | Put (evicts oldest if full)     |
 *  | /entries/{key}            | GET    | Get (refreshes access timestamp)|
 *  | /state                    | GET    | View entries by access time     |
 *  | /                         | DELETE | Clear entire LRU cache          |
 *  +---------------------------+--------+---------------------------------+
 * </pre>
 *
 * @see com.example.redis.service.impl.LruCacheService
 */
@RestController
@RequestMapping("/api/cache/lru")
@Tag(name = "LRU Cache", description = "Least Recently Used cache backed by Redis sorted set (score = timestamp)")
public class LruCacheController {

    private final CacheService cacheService;

    public LruCacheController(@Qualifier("lruCacheService") CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * Puts a key-value pair into the LRU cache. If at capacity, the least
     * recently used entry (oldest timestamp) is evicted first.
     *
     * @param request key and value to cache
     * @return confirmation with the cached key
     */
    @Operation(summary = "Put a key-value pair into the LRU cache")
    @PostMapping("/entries")
    public ResponseEntity<ApiResponse<String>> put(@Valid @RequestBody CachePutRequest request) {
        cacheService.put(request.getKey(), request.getValue());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Cached with LRU policy", request.getKey()));
    }

    /**
     * Gets a value by key. On hit, refreshes the access timestamp in the ZSET
     * so the entry moves to the "most recently used" end.
     *
     * @param key cache key to look up
     * @return the cached value, or a MISS indicator
     */
    @Operation(summary = "Get a value by key (updates access timestamp)")
    @GetMapping("/entries/{key}")
    public ResponseEntity<ApiResponse<String>> get(@PathVariable String key) {
        String value = cacheService.get(key);
        if (value == null) {
            return ResponseEntity.ok(ApiResponse.ok("Cache MISS", null));
        }
        return ResponseEntity.ok(ApiResponse.ok("Cache HIT", value));
    }

    /**
     * Returns the full cache state with all entries sorted by access time
     * (oldest first), current size, and capacity.
     *
     * @return {@link CacheStateResponse} ordered by recency
     */
    @Operation(summary = "View full cache state — entries sorted by access time (oldest first)")
    @GetMapping("/state")
    public ResponseEntity<ApiResponse<CacheStateResponse>> getState() {
        return ResponseEntity.ok(ApiResponse.ok(cacheService.getState()));
    }

    /**
     * Clears the entire LRU cache (both ZSET and HASH).
     *
     * @return confirmation message
     */
    @Operation(summary = "Clear the entire LRU cache")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clear() {
        cacheService.clear();
        return ResponseEntity.ok(ApiResponse.ok("LRU cache cleared", null));
    }
}
