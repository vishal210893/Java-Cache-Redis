# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build (includes checkstyle validation phase)
./mvnw clean compile

# Run
./mvnw spring-boot:run

# Run tests
./mvnw test

# Checkstyle only
./mvnw checkstyle:check

# Package (skip tests)
./mvnw package -DskipTests
```

The app runs on port 8081. Requires Redis on localhost:6379 and PostgreSQL (Aiven Cloud, connection in application.yaml).

## Architecture

Spring Boot 3.4 / Java 17 application demonstrating Redis sorted sets (leaderboard API) and six caching patterns with a three-tier cache hierarchy.

### Two Feature Domains

**Leaderboard (Redis Sorted Sets):** `LeaderboardController` -> `LeaderboardService` -> `SortedSetRepository` -> `RedisTemplate.opsForZSet()`. Each board is a ZSET keyed `leaderboard:{boardName}`. The repository is a thin 1:1 wrapper over Redis ZSET commands with debug logging.

**Caching Patterns:** Six patterns sharing a common structure via `CachingPatternBaseController` (abstract) and `CachingPatternService` (interface extending `CacheService`). Each pattern has its own controller/service pair:
- Cache-Aside, Read-Through, Write-Through, Write-Back, Write-Around, Refresh-Ahead
- Three-tier: L1 (Caffeine in-process) -> L2 (Redis hash `pattern:{name}:cache`) -> DB (PostgreSQL `cache_products` table)
- Each pattern is data-partitioned by `patternName` column so patterns don't interfere with each other
- LRU and LFU caches are Redis-only (no DB tier), implement `CacheService` directly

### Key Abstractions

- `CacheService`: base interface (put/get/getState/clear) for all cache types
- `CachingPatternService extends CacheService`: adds DB-aware ops (getFromDb, seedDb, buildState, flush)
- `CachingPatternBaseController`: abstract controller providing shared CRUD + DB seed endpoints; subclasses supply `getService()` and `getPatternName()`
- `DatabaseService`: PostgreSQL access partitioned by pattern name, uses `CacheProductRepository` (Spring Data JPA)
- `CachingPatternConfig`: shared beans — `cachingPatternExecutor` thread pool (write-back/refresh-ahead) and `caffeineL1Cache`

### Redis Key Conventions

- Leaderboard ZSETs: `leaderboard:{boardName}`
- Caching pattern L2 hashes: `pattern:{patternName}:cache`
- Caching pattern metadata: `pattern:{patternName}:meta`
- LRU/LFU: `cache:lru:*` / `cache:lfu:*`

## Code Style

- Uses Lombok heavily (@Data, @Builder, @RequiredArgsConstructor, @Slf4j)
- Checkstyle runs at Maven `validate` phase — config in `checkstyle.xml` (Google-based, relaxed for Lombok)
- Key checkstyle rules: 150 char line length, 500 line file limit, 60 line method limit, no star imports, no tabs
- camelCase for members/methods/params/locals, UPPER_SNAKE for constants
- All serialization is `StringRedisSerializer` (human-readable Redis keys/values)
- Swagger/OpenAPI via springdoc (`/swagger-ui.html`)

## API Base Paths

- Leaderboard: `/api/leaderboards/{boardName}`
- Caching patterns: `/api/cache/patterns/{pattern-name}` (cache-aside, read-through, write-through, write-back, write-around, refresh-ahead)
- LRU: `/api/cache/lru`
- LFU: `/api/cache/lfu`
