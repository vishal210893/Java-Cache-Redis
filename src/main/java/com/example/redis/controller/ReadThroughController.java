package com.example.redis.controller;

import com.example.redis.service.CachingPatternService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Read-Through Controller                                   |
 * +------------------------------------------------------------+
 * |  REST controller for the Read-Through caching pattern with |
 * |  stampede protection. Inherits standard CRUD + DB seed     |
 * |  endpoints from {@link CachingPatternBaseController}.      |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Pattern summary: cache handles miss internally. On L2 miss,
 * a SETNX distributed lock ensures only one thread loads from DB
 * per key -- preventing cache stampede.
 *
 * <p>Endpoints (base path {@code /api/cache/patterns/read-through}):
 * <pre>
 *  +---------------------------+--------+-------------------------------+
 *  | Endpoint                  | Method | Description                   |
 *  +---------------------------+--------+-------------------------------+
 *  | /entries                  | POST   | Write (DB + invalidate L1+L2) |
 *  | /entries/{key}            | GET    | Read (L1 -&gt; L2 -&gt; SETNX DB)  |
 *  | /state                    | GET    | View cache + DB state         |
 *  | /                         | DELETE | Clear cache (DB preserved)    |
 *  | /db/seed                  | POST   | Seed DB with test data        |
 *  | /db/state                 | GET    | View raw database contents    |
 *  +---------------------------+--------+-------------------------------+
 * </pre>
 *
 * @see com.example.redis.service.impl.ReadThroughCacheService
 */
@RestController
@RequestMapping("/api/cache/patterns/read-through")
@Tag(name = "2. Read-Through",
        description = "Cache library handles miss internally with stampede protection. Only one thread loads per key on miss.")
public class ReadThroughController extends CachingPatternBaseController {

    private final CachingPatternService service;

    public ReadThroughController(@Qualifier("readThroughService") CachingPatternService service) {
        this.service = service;
    }

    @Override
    protected CachingPatternService getService() {
        return service;
    }

    @Override
    protected String getPatternName() {
        return "Read-Through";
    }
}
