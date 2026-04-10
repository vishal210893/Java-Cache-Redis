package com.example.redis.controller;

import com.example.redis.service.CachingPatternService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Write-Through Controller                                  |
 * +------------------------------------------------------------+
 * |  REST controller for the synchronous Write-Through caching |
 * |  pattern. Inherits standard CRUD + DB seed endpoints from  |
 * |  {@link CachingPatternBaseController}.                     |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Pattern summary: writes persist to DB first (synchronous), then
 * propagate to L2 (Redis) and L1 (Caffeine). Guarantees strong
 * read-after-write consistency. Write latency = DB + L2 + L1.
 *
 * <p>Endpoints (base path {@code /api/cache/patterns/write-through}):
 * <pre>
 *  +---------------------------+--------+-------------------------------+
 *  | Endpoint                  | Method | Description                   |
 *  +---------------------------+--------+-------------------------------+
 *  | /entries                  | POST   | Write (DB -&gt; L2 -&gt; L1 sync)  |
 *  | /entries/{key}            | GET    | Read (L1 -&gt; L2 -&gt; DB)        |
 *  | /state                    | GET    | View cache + DB state         |
 *  | /                         | DELETE | Clear cache (DB preserved)    |
 *  | /db/seed                  | POST   | Seed DB with test data        |
 *  | /db/state                 | GET    | View raw database contents    |
 *  +---------------------------+--------+-------------------------------+
 * </pre>
 *
 * @see com.example.redis.service.impl.WriteThroughCacheService
 */
@RestController
@RequestMapping("/api/cache/patterns/write-through")
@Tag(name = "3. Write-Through",
        description = "Writes go to DB first (synchronous), then to cache. Strong read-after-write consistency. Write latency = DB + cache.")
public class WriteThroughController extends CachingPatternBaseController {

    private final CachingPatternService service;

    public WriteThroughController(@Qualifier("writeThroughService") CachingPatternService service) {
        this.service = service;
    }

    @Override
    protected CachingPatternService getService() {
        return service;
    }

    @Override
    protected String getPatternName() {
        return "Write-Through";
    }
}
