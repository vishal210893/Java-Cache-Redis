package com.example.redis.controller;

import com.example.redis.dto.ApiResponse;
import com.example.redis.service.CachingPatternService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Write-Back (Write-Behind) Controller                      |
 * +------------------------------------------------------------+
 * |  REST controller for the async Write-Back caching pattern. |
 * |  Inherits standard CRUD + DB seed endpoints from           |
 * |  {@link CachingPatternBaseController} and adds a manual    |
 * |  flush endpoint.                                           |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Pattern summary: writes go to L1 + L2 cache only (~1ms). A
 * background worker periodically flushes dirty keys to PostgreSQL.
 * Best for counters, analytics, and write-heavy workloads where
 * slight data loss risk on crash is acceptable.
 *
 * <p>Endpoints (base path {@code /api/cache/patterns/write-back}):
 * <pre>
 *  +---------------------------+--------+---------------------------------+
 *  | Endpoint                  | Method | Description                     |
 *  +---------------------------+--------+---------------------------------+
 *  | /entries                  | POST   | Write (L1+L2+dirty, skip DB)    |
 *  | /entries/{key}            | GET    | Read (L1 -&gt; L2 -&gt; DB)          |
 *  | /state                    | GET    | View cache + DB + dirty keys    |
 *  | /                         | DELETE | Flush + clear cache             |
 *  | /flush                    | POST   | Manual flush dirty keys to DB   |
 *  | /db/seed                  | POST   | Seed DB with test data          |
 *  | /db/state                 | GET    | View raw database contents      |
 *  +---------------------------+--------+---------------------------------+
 * </pre>
 *
 * @see com.example.redis.service.impl.WriteBackCacheService
 */
@RestController
@RequestMapping("/api/cache/patterns/write-back")
@Tag(name = "4. Write-Back (Write-Behind)",
        description = "Writes go to cache only (~1ms). Background worker flushes dirty keys to DB asynchronously. Best for counters and analytics.")
public class WriteBackController extends CachingPatternBaseController {

    private final CachingPatternService service;

    public WriteBackController(@Qualifier("writeBackService") CachingPatternService service) {
        this.service = service;
    }

    @Override
    protected CachingPatternService getService() {
        return service;
    }

    @Override
    protected String getPatternName() {
        return "Write-Back";
    }

    /**
     * Manually triggers a flush of all dirty keys from L2 (Redis) to
     * PostgreSQL. Useful for testing or forcing immediate persistence
     * without waiting for the background flush interval.
     *
     * @return confirmation that the flush completed
     */
    @Operation(summary = "Manually flush dirty keys from cache to DB")
    @PostMapping("/flush")
    public ResponseEntity<ApiResponse<String>> flush() {
        service.flush();
        return ResponseEntity.ok(ApiResponse.ok("Flush completed", "dirty keys written to DB"));
    }
}
