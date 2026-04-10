package com.example.redis.controller;

import com.example.redis.dto.ApiResponse;
import com.example.redis.dto.CachePutRequest;
import com.example.redis.dto.CachingPatternStateResponse;
import com.example.redis.dto.SeedDbRequest;
import com.example.redis.service.CachingPatternService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Caching Pattern Base Controller                           |
 * +------------------------------------------------------------+
 * |  Abstract base controller providing standard CRUD and DB   |
 * |  seed endpoints shared by all caching pattern controllers. |
 * |  Subclasses supply the concrete {@link CachingPatternService}|
 * |  and a human-readable pattern name.                        |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Endpoints provided:
 * <pre>
 *  +---------------------------+--------+-------------------------------+
 *  | Endpoint                  | Method | Description                   |
 *  +---------------------------+--------+-------------------------------+
 *  | /entries                  | POST   | Write a key-value entry       |
 *  | /entries/{key}            | GET    | Read a value by key           |
 *  | /state                    | GET    | View cache + DB state         |
 *  | /                         | DELETE | Clear cache (DB preserved)    |
 *  | /db/seed                  | POST   | Seed DB with test data        |
 *  | /db/state                 | GET    | View raw database contents    |
 *  +---------------------------+--------+-------------------------------+
 * </pre>
 *
 * @see CachingPatternService
 */
public abstract class CachingPatternBaseController {

    /**
     * Returns the concrete service implementation for the caching pattern.
     *
     * @return the pattern-specific {@link CachingPatternService}
     */
    protected abstract CachingPatternService getService();

    /**
     * Returns the human-readable name of the caching pattern (e.g., "Cache-Aside").
     *
     * @return pattern name used in API response messages
     */
    protected abstract String getPatternName();

    /**
     * Writes a key-value entry through the caching pattern's write path.
     *
     * @param request the key and value to store
     * @return confirmation with the stored key
     */
    @Operation(summary = "Write a key-value entry")
    @PostMapping("/entries")
    public ResponseEntity<ApiResponse<String>> put(@Valid @RequestBody CachePutRequest request) {
        getService().put(request.getKey(), request.getValue());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Stored via " + getPatternName(), request.getKey()));
    }

    /**
     * Reads a value by key through the caching pattern's read path (L1, L2, DB).
     *
     * @param key the cache key to look up
     * @return the value if found, or a cache MISS indicator
     */
    @Operation(summary = "Read a value by key")
    @GetMapping("/entries/{key}")
    public ResponseEntity<ApiResponse<String>> get(@PathVariable String key) {
        String value = getService().get(key);
        if (value == null) {
            return ResponseEntity.ok(ApiResponse.ok("Cache MISS (not in DB either)", null));
        }
        return ResponseEntity.ok(ApiResponse.ok("Value retrieved", value));
    }

    /**
     * Returns the full cache state including L2 entries, DB entries, and
     * pattern-specific metadata.
     *
     * @return {@link CachingPatternStateResponse} with all tier details
     */
    @Operation(summary = "View cache state, DB state, and pattern metadata")
    @GetMapping("/state")
    public ResponseEntity<ApiResponse<CachingPatternStateResponse>> getState() {
        return ResponseEntity.ok(ApiResponse.ok(getService().buildState()));
    }

    /**
     * Clears all cache tiers (L1 + L2). Database data is preserved.
     *
     * @return confirmation message
     */
    @Operation(summary = "Clear cache (DB data preserved)")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> clear() {
        getService().clear();
        return ResponseEntity.ok(ApiResponse.ok(getPatternName() + " cache cleared", null));
    }

    /**
     * Seeds the simulated database with test data entries, bypassing the cache.
     *
     * @param request map of key-value entries to seed
     * @return the number of entries seeded
     */
    @Operation(summary = "Seed simulated database with test data")
    @PostMapping("/db/seed")
    public ResponseEntity<ApiResponse<Integer>> seedDb(@Valid @RequestBody SeedDbRequest request) {
        getService().seedDb(request.getEntries());
        return ResponseEntity.ok(ApiResponse.ok("DB seeded", request.getEntries().size()));
    }

    /**
     * Returns all entries currently in the simulated database for this pattern.
     *
     * @return map of all database key-value pairs
     */
    @Operation(summary = "View raw database contents")
    @GetMapping("/db/state")
    public ResponseEntity<ApiResponse<Map<String, String>>> getDbState() {
        return ResponseEntity.ok(ApiResponse.ok(getService().getDbState()));
    }
}
