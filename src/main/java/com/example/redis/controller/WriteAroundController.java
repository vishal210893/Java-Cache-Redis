package com.example.redis.controller;

import com.example.redis.service.CachingPatternService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Write-Around Controller                                   |
 * +------------------------------------------------------------+
 * |  REST controller for the Write-Around caching pattern.     |
 * |  Inherits standard CRUD + DB seed endpoints from           |
 * |  {@link CachingPatternBaseController}.                     |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Pattern summary: writes go directly to DB, bypassing L1 + L2
 * cache entirely. Existing cache entries are invalidated. Reads
 * populate the cache on miss. Best for write-heavy, read-light
 * workloads (logs, audit trails, batch results).
 *
 * <p>Endpoints (base path {@code /api/cache/patterns/write-around}):
 * <pre>
 *  +---------------------------+--------+-------------------------------+
 *  | Endpoint                  | Method | Description                   |
 *  +---------------------------+--------+-------------------------------+
 *  | /entries                  | POST   | Write (DB only + invalidate)  |
 *  | /entries/{key}            | GET    | Read (L1 -&gt; L2 -&gt; DB)        |
 *  | /state                    | GET    | View cache + DB state         |
 *  | /                         | DELETE | Clear cache (DB preserved)    |
 *  | /db/seed                  | POST   | Seed DB with test data        |
 *  | /db/state                 | GET    | View raw database contents    |
 *  +---------------------------+--------+-------------------------------+
 * </pre>
 *
 * @see com.example.redis.service.impl.WriteAroundCacheService
 */
@RestController
@RequestMapping("/api/cache/patterns/write-around")
@Tag(name = "5. Write-Around",
        description = "Writes go directly to DB, bypassing cache. Reads populate cache on miss. Best for write-heavy, read-light workloads.")
public class WriteAroundController extends CachingPatternBaseController {

    private final CachingPatternService service;

    public WriteAroundController(@Qualifier("writeAroundService") CachingPatternService service) {
        this.service = service;
    }

    @Override
    protected CachingPatternService getService() {
        return service;
    }

    @Override
    protected String getPatternName() {
        return "Write-Around";
    }
}
