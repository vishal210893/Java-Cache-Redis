package com.example.redis.controller;

import com.example.redis.service.CachingPatternService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Refresh-Ahead Controller                                  |
 * +------------------------------------------------------------+
 * |  REST controller for the Refresh-Ahead caching pattern.    |
 * |  Inherits standard CRUD + DB seed endpoints from           |
 * |  {@link CachingPatternBaseController}.                     |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Pattern summary: proactively refreshes cache entries approaching
 * TTL expiry. When an entry enters the last 20% of its TTL, a
 * background thread reloads the value from DB while the stale value
 * is returned immediately. Hot keys never experience miss latency.
 *
 * <p>Endpoints (base path {@code /api/cache/patterns/refresh-ahead}):
 * <pre>
 *  +---------------------------+--------+-------------------------------+
 *  | Endpoint                  | Method | Description                   |
 *  +---------------------------+--------+-------------------------------+
 *  | /entries                  | POST   | Write (DB+L2+L1+TTL)         |
 *  | /entries/{key}            | GET    | Read (L1-&gt;L2-&gt;DB, TTL check) |
 *  | /state                    | GET    | View cache + DB + TTL state   |
 *  | /                         | DELETE | Clear cache (DB preserved)    |
 *  | /db/seed                  | POST   | Seed DB with test data        |
 *  | /db/state                 | GET    | View raw database contents    |
 *  +---------------------------+--------+-------------------------------+
 * </pre>
 *
 * @see com.example.redis.service.impl.RefreshAheadCacheService
 */
@RestController
@RequestMapping("/api/cache/patterns/refresh-ahead")
@Tag(name = "6. Refresh-Ahead",
        description = "Proactively refreshes entries about to expire. Hot keys never see miss latency. Async refresh in last 20% of TTL.")
public class RefreshAheadController extends CachingPatternBaseController {

    private final CachingPatternService service;

    public RefreshAheadController(@Qualifier("refreshAheadService") CachingPatternService service) {
        this.service = service;
    }

    @Override
    protected CachingPatternService getService() {
        return service;
    }

    @Override
    protected String getPatternName() {
        return "Refresh-Ahead";
    }
}
