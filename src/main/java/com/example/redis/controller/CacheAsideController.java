package com.example.redis.controller;

import com.example.redis.service.CachingPatternService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Cache-Aside Controller                                    |
 * +------------------------------------------------------------+
 * |  REST controller for the Cache-Aside (Lazy Loading)        |
 * |  caching pattern. Inherits standard CRUD + DB seed         |
 * |  endpoints from {@link CachingPatternBaseController}.      |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Pattern summary: the application manages the cache explicitly.
 * On read miss, it fetches from DB and populates L1 + L2. On write,
 * it updates DB and invalidates both cache tiers.
 *
 * <p>Endpoints (base path {@code /api/cache/patterns/cache-aside}):
 * <pre>
 *  +---------------------------+--------+-------------------------------+
 *  | Endpoint                  | Method | Description                   |
 *  +---------------------------+--------+-------------------------------+
 *  | /entries                  | POST   | Write (DB + invalidate L1+L2) |
 *  | /entries/{key}            | GET    | Read (L1 -&gt; L2 -&gt; DB)        |
 *  | /state                    | GET    | View cache + DB state         |
 *  | /                         | DELETE | Clear cache (DB preserved)    |
 *  | /db/seed                  | POST   | Seed DB with test data        |
 *  | /db/state                 | GET    | View raw database contents    |
 *  +---------------------------+--------+-------------------------------+
 * </pre>
 *
 * @see com.example.redis.service.impl.CacheAsideCacheService
 */
@RestController
@RequestMapping("/api/cache/patterns/cache-aside")
@Tag(name = "1. Cache-Aside (Lazy Loading)",
        description = "App manages cache. On miss, app fetches DB and populates cache. On write, app updates DB and invalidates cache.")
public class CacheAsideController extends CachingPatternBaseController {

    private final CachingPatternService service;

    public CacheAsideController(@Qualifier("cacheAsideService") CachingPatternService service) {
        this.service = service;
    }

    @Override
    protected CachingPatternService getService() {
        return service;
    }

    @Override
    protected String getPatternName() {
        return "Cache-Aside";
    }
}
