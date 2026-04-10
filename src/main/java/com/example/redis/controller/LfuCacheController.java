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
 * |  LFU Cache Controller                                      |
 * +------------------------------------------------------------+
 * |  REST controller for the Least Frequently Used eviction    |
 * |  cache backed by Redis (HASH + ZSET with frequency         |
 * |  scores). Evicts the entry with the lowest access count    |
 * |  when capacity is reached.                                 |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Endpoints (base path {@code /api/cache/lfu}):
 * <pre>
 *  +---------------------------+--------+---------------------------------+
 *  | Endpoint                  | Method | Description                     |
 *  +---------------------------+--------+---------------------------------+
 *  | /entries                  | POST   | Put (evicts least frequent)     |
 *  | /entries/{key}            | GET    | Get (increments frequency)      |
 *  | /state                    | GET    | View entries by frequency       |
 *  | /                         | DELETE | Clear entire LFU cache          |
 *  +---------------------------+--------+---------------------------------+
 * </pre>
 *
 * @see com.example.redis.service.impl.LfuCacheService
 */
@RestController
@RequestMapping("/api/cache/lfu")
@Tag(name = "LFU Cache", description = "Least Frequently Used cache backed by Redis sorted set (score = access count)")
public class LfuCacheController {

    private final CacheService cacheService;

    public LfuCacheController(@Qualifier("lfuCacheService") CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * Puts a key-value pair into the LFU cache. If at capacity and the key is
     * new, the least frequently used entry (lowest score) is evicted first.
     * Existing keys get their frequency incremented.
     *
     * @param request key and value to cache
     * @return confirmation with the cached key
     */
    @Operation(summary = "Put a key-value pair into the LFU cache")
    @PostMapping("/entries")
    public ResponseEntity<ApiResponse<String>> put(@Valid @RequestBody CachePutRequest request) {
        cacheService.put(request.getKey(), request.getValue());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Cached with LFU policy", request.getKey()));
    }

    /**
     * Gets a value by key. On hit, increments the access frequency in the ZSET
     * via {@code ZINCRBY +1} so frequently accessed keys are protected from
     * eviction.
     *
     * @param key cache key to look up
     * @return the cached value, or a MISS indicator
     */
    @Operation(summary = "Get a value by key (increments access frequency)")
    @GetMapping("/entries/{key}")
    public ResponseEntity<ApiResponse<String>> get(@PathVariable String key) {
        String value = cacheService.get(key);
        if (value == null) {
            return ResponseEntity.ok(ApiResponse.ok("Cache MISS", null));
        }
        return ResponseEntity.ok(ApiResponse.ok("Cache HIT", value));
    }

    /**
     * Returns the full cache state with all entries sorted by frequency
     * (lowest first), current size, and capacity.
     *
     * @return {@link CacheStateResponse} ordered by access frequency
     */
    @Operation(summary = "View full cache state — entries sorted by frequency (lowest first)")
    @GetMapping("/state")
    public ResponseEntity<ApiResponse<CacheStateResponse>> getState() {
        return ResponseEntity.ok(ApiResponse.ok(cacheService.getState()));
    }

    /**
     * Clears the entire LFU cache (both ZSET and HASH).
     *
     * @return confirmation message
     */
    @Operation(summary = "Clear the entire LFU cache")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clear() {
        cacheService.clear();
        return ResponseEntity.ok(ApiResponse.ok("LFU cache cleared", null));
    }
}
