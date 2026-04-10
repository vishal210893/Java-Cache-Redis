# Cache Learning - Redis, Caffeine & Caching Patterns

![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-green?logo=springboot)
![Redis](https://img.shields.io/badge/Redis-6+-red?logo=redis)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql)
![Caffeine](https://img.shields.io/badge/Caffeine-3.x-orange)
![License](https://img.shields.io/badge/License-MIT-yellow)

A hands-on Spring Boot project for learning Redis data structures, cache eviction policies, and production caching patterns through a working REST API.

---

## Overview

This project is a practical learning tool for understanding how caching works at multiple levels in a real application. Instead of reading about caching patterns in theory, you can run the app, hit endpoints, and observe exactly how data flows between cache tiers and the database.

### What You Will Learn

- **Redis Sorted Sets** -- ZADD, ZSCORE, ZREVRANGE, ZINCRBY, and other ZSET commands through a leaderboard API
- **LRU Eviction** -- Least Recently Used cache built on Redis ZSET with timestamp-based scores
- **LFU Eviction** -- Least Frequently Used cache built on Redis ZSET with frequency-based scores
- **6 Caching Patterns** -- Cache-Aside, Read-Through, Write-Through, Write-Back, Write-Around, and Refresh-Ahead
- **Multi-Tier Caching** -- L1 in-process cache (Caffeine) sitting in front of L2 remote cache (Redis) backed by PostgreSQL
- **Observability** -- Swagger UI, Actuator health endpoints, and structured logging to trace cache hits, misses, and evictions

### Architecture

The caching patterns use a three-tier hierarchy:

```
                         +------------------+
                         |     Client       |
                         |  (curl/Postman)  |
                         +--------+---------+
                                  |
                                  v
                    +-----------------------------+
                    |     Spring Boot App         |
                    |        (port 8081)          |
                    |                             |
                    |  +---Controller Layer----+  |
                    |  | Leaderboard | LRU/LFU |  |
                    |  | 6 Pattern Controllers |  |
                    |  +-----------+-----------+  |
                    |              |               |
                    |  +---Service Layer--------+  |
                    |  |                        |  |
                    |  |  +--L1: Caffeine---+   |  |
                    |  |  | In-Process Cache|   |  |
                    |  |  | (max 100, 5min) |   |  |
                    |  |  +-------+---------+   |  |
                    |  |          | MISS         |  |
                    |  |          v              |  |
                    |  |  +--L2: Redis------+   |  |
                    |  |  | Remote Cache    |   |  |
                    |  |  | (Hash per       |   |  |
                    |  |  |  pattern)       |   |  |
                    |  |  +-------+---------+   |  |
                    |  |          | MISS         |  |
                    |  |          v              |  |
                    |  |  +--DB: PostgreSQL-+   |  |
                    |  |  | cache_products  |   |  |
                    |  |  | table           |   |  |
                    |  |  +-----------------+   |  |
                    |  +------------------------+  |
                    +-----------------------------+
```

The Leaderboard and LRU/LFU caches interact with Redis directly (no DB tier). The six caching patterns each demonstrate a different strategy for coordinating reads and writes across all three tiers.

---

## Tech Stack

| Technology        | Version | Purpose                                          |
|-------------------|---------|--------------------------------------------------|
| Java              | 17      | Language runtime                                 |
| Spring Boot       | 4.0.5   | Application framework                            |
| Spring Data Redis | 3.4.x   | Redis client (Lettuce driver)                    |
| Spring Data JPA   | 3.4.x   | PostgreSQL ORM (Hibernate)                       |
| Redis             | 6+      | In-memory data store (L2 cache + sorted sets)    |
| PostgreSQL        | 16      | Relational database (backing store)              |
| Caffeine          | 3.x     | High-performance in-process cache (L1)           |
| Lombok            | 1.18.x  | Boilerplate reduction (@Data, @Builder, @Slf4j)  |
| springdoc-openapi | 2.5.0   | Swagger UI / OpenAPI 3 documentation             |
| Maven Checkstyle  | 3.6.0   | Code style enforcement (Google-based)            |

---

## Features

The application exposes 9 REST API endpoint groups across two feature domains:

### Leaderboard (Redis Sorted Sets)

1. **Leaderboard API** -- Full CRUD over Redis sorted sets. Add members with scores, query top-N, filter by rank range or score range, increment scores atomically, and inspect board size. Maps 1:1 to Redis ZSET commands (ZADD, ZSCORE, ZREVRANGE, ZRANGEBYSCORE, ZINCRBY, ZREM, ZCARD, ZCOUNT).

### Cache Eviction Policies

2. **LRU Cache** -- Least Recently Used eviction backed by Redis HASH + ZSET. The ZSET score is a timestamp; on each access the timestamp refreshes. When capacity (default 5) is reached, the oldest-accessed entry is evicted.

3. **LFU Cache** -- Least Frequently Used eviction backed by Redis HASH + ZSET. The ZSET score is an access counter; each read increments it via ZINCRBY. When capacity (default 5) is reached, the least-accessed entry is evicted.

### Caching Patterns (3-Tier: Caffeine -> Redis -> PostgreSQL)

4. **Cache-Aside** -- Application checks cache first; on miss, fetches from DB and populates cache. Writes go to DB only; cache is invalidated or updated separately.

5. **Read-Through** -- Uses Caffeine `LoadingCache` with a loader function that fetches from Redis or DB on miss. Provides stampede protection (only one thread loads a missing key).

6. **Write-Through** -- Writes go synchronously to both cache (L1 + L2) and DB in the same request. Guarantees consistency at the cost of write latency.

7. **Write-Back (Write-Behind)** -- Writes go to cache only (L1 + L2) and are marked "dirty." A `@Scheduled` background task flushes dirty keys to PostgreSQL every 10 seconds. Includes a manual `/flush` endpoint. Best for write-heavy workloads where slight data loss risk on crash is acceptable.

8. **Write-Around** -- Writes go directly to DB, bypassing the cache entirely. Reads check cache first; on miss, fetch from DB and populate cache. Prevents cache pollution from infrequently-read writes.

9. **Refresh-Ahead** -- Uses Caffeine `refreshAfterWrite` to proactively refresh entries in the background before they expire. Reads always return the cached value while a background thread reloads from Redis/DB. Best for read-heavy workloads with predictable access patterns.

---

## Prerequisites

- **Java 21+** -- [Download](https://adoptium.net/)
- **Maven 3.8+** -- [Download](https://maven.apache.org/download.cgi) (or use the included `./mvnw` wrapper)
- **Redis 6+** -- running on `localhost:6379`
- **PostgreSQL** -- cloud or local instance (see setup options below)
- **Git** -- for cloning the repository

---

## Installation & Setup

### 1. Clone the Repository

```bash
git clone https://github.com/your-username/Java_Redis.git
cd Java_Redis
```

### 2. Install Redis

**macOS (Homebrew):**

```bash
brew install redis
brew services start redis
```

**Ubuntu/Debian:**

```bash
sudo apt update
sudo apt install redis-server
sudo systemctl start redis-server
sudo systemctl enable redis-server
```

**Docker:**

```bash
docker run -d --name redis -p 6379:6379 redis:latest
```

**Verify Redis is running:**

```bash
redis-cli ping
```

Expected output: `PONG`

### 3. Setup PostgreSQL

Choose one of the following options:

#### Option A: Aiven Free Tier (Recommended for Learning)

Aiven provides a free-tier PostgreSQL instance that requires no local installation:

1. Sign up at [https://aiven.io/](https://aiven.io/)
2. Create a free PostgreSQL service
3. Copy the connection URI from the Aiven console
4. Update `src/main/resources/application.yaml` with your connection details:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://your-host.aivencloud.com:port/defaultdb?sslmode=require
    username: avnadmin
    password: ${DB_PASSWORD}
```

#### Option B: Local Docker

```bash
docker run -d \
  --name postgres \
  -p 5432:5432 \
  -e POSTGRES_DB=cachelearning \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=secret \
  postgres:16
```

Then update `application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/cachelearning
    username: postgres
    password: ${DB_PASSWORD}
```

#### Option C: Local Install

**macOS:**

```bash
brew install postgresql@16
brew services start postgresql@16
createdb cachelearning
```

**Ubuntu:**

```bash
sudo apt install postgresql
sudo systemctl start postgresql
sudo -u postgres createdb cachelearning
```

### 4. Configure Database Password

Set the `DB_PASSWORD` environment variable before running the application:

```bash
export DB_PASSWORD=your_password_here
```

The `application.yaml` references this via `${DB_PASSWORD}`. Alternatively, you can edit the file directly (not recommended for shared or production environments).

The `cache_products` table is created automatically by Hibernate on first startup (`ddl-auto: update`).

### 5. Build and Run

```bash
# Build (runs checkstyle validation automatically)
./mvnw clean compile

# Run the application
./mvnw spring-boot:run
```

Or using system Maven:

```bash
mvn clean compile
mvn spring-boot:run
```

### 6. Verify

Once the application starts, you will see a banner in the console listing all endpoints.

- **Health check:** [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)
- **Swagger UI:** [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html)
- **Actuator info:** [http://localhost:8081/actuator/info](http://localhost:8081/actuator/info)

The health endpoint should return:

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" }
  }
}
```

---

## API Endpoints Quick Reference

| Group           | Base Path                                | Methods                    | Description                          |
|-----------------|------------------------------------------|----------------------------|--------------------------------------|
| Leaderboard     | `/api/leaderboards/{boardName}`          | POST, GET, PATCH, DELETE   | Redis sorted set CRUD operations     |
| LRU Cache       | `/api/cache/lru`                         | POST, GET, DELETE          | Timestamp-based eviction cache       |
| LFU Cache       | `/api/cache/lfu`                         | POST, GET, DELETE          | Frequency-based eviction cache       |
| Cache-Aside     | `/api/cache/patterns/cache-aside`        | POST, GET, DELETE          | Lazy-load caching pattern            |
| Read-Through    | `/api/cache/patterns/read-through`       | POST, GET, DELETE          | LoadingCache with stampede protection|
| Write-Through   | `/api/cache/patterns/write-through`      | POST, GET, DELETE          | Synchronous DB + cache writes        |
| Write-Back      | `/api/cache/patterns/write-back`         | POST, GET, DELETE + flush  | Async background DB writes           |
| Write-Around    | `/api/cache/patterns/write-around`       | POST, GET, DELETE          | Cache bypass on write                |
| Refresh-Ahead   | `/api/cache/patterns/refresh-ahead`      | POST, GET, DELETE          | Proactive background refresh         |

All caching pattern endpoints also expose `/db/seed` (POST) and `/db/state` (GET) for database inspection.

---

## Usage Guide

### Getting Started with Leaderboard (Sorted Sets)

The leaderboard API maps directly to Redis ZSET commands. Each leaderboard is stored as a sorted set keyed `leaderboard:{boardName}`.

**Add members to a leaderboard:**

```bash
curl -X POST http://localhost:8081/api/leaderboards/game1/members \
  -H "Content-Type: application/json" \
  -d '{"memberId": "alice", "score": 1500}'

curl -X POST http://localhost:8081/api/leaderboards/game1/members \
  -H "Content-Type: application/json" \
  -d '{"memberId": "bob", "score": 2200}'

curl -X POST http://localhost:8081/api/leaderboards/game1/members \
  -H "Content-Type: application/json" \
  -d '{"memberId": "charlie", "score": 1800}'
```

**Get the top 3 members (ZREVRANGE):**

```bash
curl http://localhost:8081/api/leaderboards/game1/top?count=3
```

```json
{
  "status": "OK",
  "data": [
    { "memberId": "bob", "score": 2200.0, "rank": 0 },
    { "memberId": "charlie", "score": 1800.0, "rank": 1 },
    { "memberId": "alice", "score": 1500.0, "rank": 2 }
  ]
}
```

**Increment a member's score (ZINCRBY):**

```bash
curl -X PATCH "http://localhost:8081/api/leaderboards/game1/members/alice/score?delta=1000"
```

**Get members in a score range (ZRANGEBYSCORE):**

```bash
curl "http://localhost:8081/api/leaderboards/game1/score-range?min=1500&max=2500"
```

### Understanding LRU vs LFU

Both caches have a default capacity of 5 entries. When the cache is full and a new entry arrives, the eviction policy decides which entry to remove.

- **LRU (Least Recently Used):** Evicts the entry that has not been accessed for the longest time. The ZSET score is the access timestamp.
- **LFU (Least Frequently Used):** Evicts the entry with the fewest total accesses. The ZSET score is an access counter incremented on each read.

**Demo sequence for LRU eviction:**

```bash
# Fill the cache to capacity (5 entries)
for i in 1 2 3 4 5; do
  curl -X POST http://localhost:8081/api/cache/lru/entries \
    -H "Content-Type: application/json" \
    -d "{\"key\": \"key$i\", \"value\": \"value$i\"}"
done

# Access key1 to refresh its timestamp (it is now "recently used")
curl http://localhost:8081/api/cache/lru/entries/key1

# View state -- key2 should have the oldest timestamp
curl http://localhost:8081/api/cache/lru/state

# Add key6 -- this should evict key2 (least recently used)
curl -X POST http://localhost:8081/api/cache/lru/entries \
  -H "Content-Type: application/json" \
  -d '{"key": "key6", "value": "value6"}'

# Confirm key2 was evicted
curl http://localhost:8081/api/cache/lru/state
```

**Demo sequence for LFU eviction:**

```bash
# Fill the cache to capacity
for i in 1 2 3 4 5; do
  curl -X POST http://localhost:8081/api/cache/lfu/entries \
    -H "Content-Type: application/json" \
    -d "{\"key\": \"key$i\", \"value\": \"value$i\"}"
done

# Access key1 three times to boost its frequency
curl http://localhost:8081/api/cache/lfu/entries/key1
curl http://localhost:8081/api/cache/lfu/entries/key1
curl http://localhost:8081/api/cache/lfu/entries/key1

# View state -- key1 should have the highest score
curl http://localhost:8081/api/cache/lfu/state

# Add key6 -- this evicts the entry with the lowest frequency
curl -X POST http://localhost:8081/api/cache/lfu/entries \
  -H "Content-Type: application/json" \
  -d '{"key": "key6", "value": "value6"}'
```

### Exploring Caching Patterns

Each caching pattern has the same base endpoint structure but implements a different strategy for coordinating cache and database operations. All patterns use the `cache_products` PostgreSQL table and are data-partitioned by pattern name so they do not interfere with each other.

#### Cache-Aside

The application checks the cache first. On miss, it fetches from the database and populates the cache for subsequent requests.

```bash
# Seed the database with test products
curl -X POST http://localhost:8081/api/cache/patterns/cache-aside/db/seed \
  -H "Content-Type: application/json" \
  -d '{"entries": {"laptop": "MacBook Pro", "phone": "iPhone 15", "tablet": "iPad Air"}}'

# First read -- cache MISS, fetches from DB, populates cache
curl http://localhost:8081/api/cache/patterns/cache-aside/entries/laptop

# Second read -- cache HIT (served from L1 or L2)
curl http://localhost:8081/api/cache/patterns/cache-aside/entries/laptop

# View state to see L1, L2, and DB contents
curl http://localhost:8081/api/cache/patterns/cache-aside/state
```

**Observe:** The first GET triggers a DB read and cache write. Subsequent GETs are served from cache. Check the `/state` endpoint to see entries across all tiers.

#### Read-Through

Similar to cache-aside, but the cache itself is responsible for loading missing data. Uses Caffeine `LoadingCache` with built-in stampede protection -- if multiple threads request the same missing key simultaneously, only one thread performs the load.

```bash
# Seed DB
curl -X POST http://localhost:8081/api/cache/patterns/read-through/db/seed \
  -H "Content-Type: application/json" \
  -d '{"entries": {"laptop": "MacBook Pro", "phone": "iPhone 15"}}'

# Read triggers automatic loading through L1 -> L2 -> DB
curl http://localhost:8081/api/cache/patterns/read-through/entries/laptop

# View state
curl http://localhost:8081/api/cache/patterns/read-through/state
```

**Observe:** The loading happens transparently. The Caffeine L1 cache manages the load, preventing thundering herd on popular keys.

#### Write-Through

Every write goes synchronously to the database and both cache tiers in a single request. Guarantees strong consistency but adds write latency.

```bash
# Write -- goes to L1, L2, and DB synchronously
curl -X POST http://localhost:8081/api/cache/patterns/write-through/entries \
  -H "Content-Type: application/json" \
  -d '{"key": "laptop", "value": "MacBook Pro M3"}'

# Read -- served from L1 cache immediately
curl http://localhost:8081/api/cache/patterns/write-through/entries/laptop

# Verify DB has the data too
curl http://localhost:8081/api/cache/patterns/write-through/db/state
```

**Observe:** The write response takes slightly longer (DB + cache), but reads are always consistent. Check both `/state` and `/db/state` to confirm all tiers have the same data.

#### Write-Back (Write-Behind)

Writes only update the cache (L1 + L2) and mark the key as "dirty." A `@Scheduled` background task flushes dirty keys to PostgreSQL every 10 seconds. This gives sub-millisecond write latency at the cost of potential data loss if the application crashes before flush.

```bash
# Write -- only goes to cache (fast, ~1ms)
curl -X POST http://localhost:8081/api/cache/patterns/write-back/entries \
  -H "Content-Type: application/json" \
  -d '{"key": "counter", "value": "42"}'

# Check state immediately -- data in cache, not yet in DB
curl http://localhost:8081/api/cache/patterns/write-back/state

# Trigger manual flush (or wait 10 seconds for scheduled flush)
curl -X POST http://localhost:8081/api/cache/patterns/write-back/flush

# Now DB has the data
curl http://localhost:8081/api/cache/patterns/write-back/db/state
```

**Observe:** After the write, `/state` shows the key in cache with a "dirty" flag. After flush (manual or scheduled), the key appears in the DB and the dirty flag is cleared.

#### Write-Around

Writes go directly to the database, completely bypassing the cache. This prevents cache pollution from data that may never be read. On read, if the key is not in cache, it is fetched from DB and then cached.

```bash
# Write -- goes directly to DB, cache is untouched
curl -X POST http://localhost:8081/api/cache/patterns/write-around/entries \
  -H "Content-Type: application/json" \
  -d '{"key": "laptop", "value": "MacBook Pro"}'

# Check state -- cache is empty, DB has the data
curl http://localhost:8081/api/cache/patterns/write-around/state

# Read -- cache MISS, loads from DB, then caches
curl http://localhost:8081/api/cache/patterns/write-around/entries/laptop

# Now cache has the entry
curl http://localhost:8081/api/cache/patterns/write-around/state
```

**Observe:** After the POST, the cache is empty. Only after the first GET does the entry appear in cache. This pattern is ideal for write-heavy workloads where most written data is rarely read.

#### Refresh-Ahead

Uses Caffeine `refreshAfterWrite` to proactively refresh entries in the background before they expire. Reads always return the current cached value while a background thread reloads from Redis/DB asynchronously.

```bash
# Seed DB and populate cache
curl -X POST http://localhost:8081/api/cache/patterns/refresh-ahead/db/seed \
  -H "Content-Type: application/json" \
  -d '{"entries": {"laptop": "MacBook Pro", "phone": "iPhone 15"}}'

curl http://localhost:8081/api/cache/patterns/refresh-ahead/entries/laptop

# Subsequent reads within the refresh window return cached value
# while background thread refreshes from DB
curl http://localhost:8081/api/cache/patterns/refresh-ahead/entries/laptop

# View state
curl http://localhost:8081/api/cache/patterns/refresh-ahead/state
```

**Observe:** Reads are always fast because the cache proactively refreshes entries before they expire. Check application logs to see background refresh activity.

---

## Observing the Data

### Redis Insight

[Redis Insight](https://redis.io/insight/) is a free GUI for inspecting Redis data.

1. Download from [https://redis.io/insight/](https://redis.io/insight/)
2. Connect to `localhost:6379`
3. Browse keys to observe:
   - `leaderboard:*` -- sorted sets for each leaderboard
   - `cache:lru:data` / `cache:lru:access` -- LRU hash and sorted set
   - `cache:lfu:data` / `cache:lfu:access` -- LFU hash and sorted set
   - `pattern:cache-aside:cache` -- Redis hash for cache-aside L2
   - `pattern:write-back:cache` -- Redis hash for write-back L2
   - `pattern:write-back:meta` -- metadata (dirty keys set)

### PostgreSQL

Query the `cache_products` table to see persisted data:

```sql
SELECT pattern_name, product_key, product_value FROM cache_products ORDER BY pattern_name, product_key;
```

Each caching pattern writes to the same table but uses a different `pattern_name` value, so patterns do not interfere with each other.

### Swagger UI

Open [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html) to browse and test all endpoints interactively. Endpoints are grouped by controller tag: Leaderboard, LRU Cache, LFU Cache, and each caching pattern.

### Application Logs

The application uses Lombok `@Slf4j` with debug-level logging in service and repository classes. To see cache hit/miss and eviction details, set the log level:

```yaml
logging:
  level:
    com.example.redis: DEBUG
```

---

## Postman Collection

A pre-built Postman collection is included:

**File:** `Redis_Sorted_Set.postman_collection.json`

**To import:**

1. Open Postman
2. Click **Import** (top left)
3. Drag the JSON file or click **Upload Files** and select it
4. The collection appears with 9 folders of pre-configured requests

**Collection variable:**

| Variable  | Value                     |
|-----------|---------------------------|
| `baseUrl` | `http://localhost:8081`   |

Each folder contains a logical sequence of requests to demonstrate the feature end-to-end.

---

## API Documentation

Detailed endpoint documentation with full request/response examples is available in:

**File:** `API_DOCUMENTATION.md`

This includes all request bodies, response schemas, HTTP status codes, and error handling for every endpoint.

---

## Project Structure

```
Java_Redis/
├── pom.xml                                    # Maven build config, dependencies
├── checkstyle.xml                             # Code style rules (Google-based)
├── API_DOCUMENTATION.md                       # Detailed API docs
├── Redis_Sorted_Set.postman_collection.json   # Postman test collection
└── src/main/
    ├── java/com/example/redis/
    │   ├── RedisApplication.java              # Spring Boot entry point + startup banner
    │   ├── config/
    │   │   ├── RedisConfig.java               # RedisTemplate with StringRedisSerializer
    │   │   └── CachingPatternConfig.java      # Caffeine L1 cache + thread pool beans
    │   ├── controller/
    │   │   ├── LeaderboardController.java     # Sorted set REST endpoints
    │   │   ├── LruCacheController.java        # LRU cache REST endpoints
    │   │   ├── LfuCacheController.java        # LFU cache REST endpoints
    │   │   ├── CachingPatternBaseController.java  # Abstract base for pattern controllers
    │   │   ├── CacheAsideController.java      # Cache-aside endpoints
    │   │   ├── ReadThroughController.java     # Read-through endpoints
    │   │   ├── WriteThroughController.java    # Write-through endpoints
    │   │   ├── WriteBackController.java       # Write-back endpoints + /flush
    │   │   ├── WriteAroundController.java     # Write-around endpoints
    │   │   └── RefreshAheadController.java    # Refresh-ahead endpoints
    │   ├── dto/
    │   │   ├── ApiResponse.java               # Uniform API response wrapper
    │   │   ├── CacheEntry.java                # Key-value-score entry DTO
    │   │   ├── CachePutRequest.java           # Cache write request body
    │   │   ├── CacheStateResponse.java        # LRU/LFU state response
    │   │   ├── CachingPatternStateResponse.java  # Multi-tier state response
    │   │   ├── MemberScoreRequest.java        # Leaderboard member request
    │   │   ├── RankedMemberResponse.java      # Leaderboard member response
    │   │   └── SeedDbRequest.java             # DB seed request body
    │   ├── exception/
    │   │   ├── GlobalExceptionHandler.java    # @ControllerAdvice error handler
    │   │   └── MemberNotFoundException.java   # 404 for leaderboard lookups
    │   ├── model/
    │   │   └── CacheProduct.java              # JPA entity for cache_products table
    │   ├── repository/
    │   │   ├── SortedSetRepository.java       # Redis ZSET operations wrapper
    │   │   └── CacheProductRepository.java    # Spring Data JPA repository
    │   └── service/
    │       ├── CacheService.java              # Base cache interface (put/get/state/clear)
    │       ├── CachingPatternService.java     # Extended interface with DB operations
    │       ├── LeaderboardService.java        # Leaderboard service interface
    │       └── impl/
    │           ├── LeaderboardServiceImpl.java    # Leaderboard business logic
    │           ├── DatabaseService.java           # Pattern-partitioned DB access
    │           ├── LruCacheService.java           # LRU eviction implementation
    │           ├── LfuCacheService.java           # LFU eviction implementation
    │           ├── CacheAsideCacheService.java    # Cache-aside implementation
    │           ├── ReadThroughCacheService.java   # Read-through implementation
    │           ├── WriteThroughCacheService.java  # Write-through implementation
    │           ├── WriteBackCacheService.java     # Write-back implementation
    │           ├── WriteAroundCacheService.java   # Write-around implementation
    │           └── RefreshAheadCacheService.java  # Refresh-ahead implementation
    └── resources/
        └── application.yaml                   # App config (ports, Redis, PostgreSQL, cache settings)
```

---

## Configuration

All configurable properties from `application.yaml`:

| Property                                       | Default        | Description                                    |
|------------------------------------------------|----------------|------------------------------------------------|
| `server.port`                                  | `8081`         | HTTP server port                               |
| `spring.data.redis.host`                       | `localhost`    | Redis server hostname                          |
| `spring.data.redis.port`                       | `6379`         | Redis server port                              |
| `spring.data.redis.timeout`                    | `2000ms`       | Redis connection timeout                       |
| `spring.data.redis.lettuce.pool.max-active`    | `8`            | Max active connections in Lettuce pool          |
| `spring.data.redis.lettuce.pool.max-idle`      | `8`            | Max idle connections in Lettuce pool            |
| `spring.data.redis.lettuce.pool.min-idle`      | `2`            | Min idle connections in Lettuce pool            |
| `spring.datasource.url`                        | (Aiven cloud)  | PostgreSQL JDBC URL                            |
| `spring.datasource.username`                   | `avnadmin`     | PostgreSQL username                            |
| `spring.datasource.password`                   | `${DB_PASSWORD}` | PostgreSQL password (env variable)           |
| `spring.datasource.hikari.maximum-pool-size`   | `10`           | Max DB connections in HikariCP pool            |
| `spring.jpa.hibernate.ddl-auto`                | `update`       | Auto-create/update schema on startup           |
| `app.cache.lru-capacity`                       | `5`            | Max entries in LRU cache                       |
| `app.cache.lfu-capacity`                       | `5`            | Max entries in LFU cache                       |
| `app.caching.executor-pool-size`               | `4`            | Thread pool for async write-back/refresh-ahead |
| `app.caching.caffeine.max-size`                | `100`          | Max entries in Caffeine L1 cache               |
| `app.caching.caffeine.expire-after-write-seconds` | `300`       | Caffeine L1 TTL (seconds)                      |
| `app.caching.write-back.flush-interval-seconds`   | `10`        | Write-back flush interval (seconds)            |
| `app.caching.refresh-ahead.ttl-seconds`        | `60`           | Refresh-ahead TTL (seconds)                    |
| `app.caching.refresh-ahead.threshold-percent`  | `80`           | Refresh-ahead trigger threshold (percent)      |
| `management.endpoints.web.exposure.include`    | `health,info,metrics` | Exposed Actuator endpoints              |
| `management.endpoint.health.show-details`      | `always`       | Show component health details                  |

---

## Caching Patterns Comparison

| Pattern        | Write Path                         | Read Path                              | Consistency       | Best For                          |
|----------------|-----------------------------------|----------------------------------------|--------------------|-----------------------------------|
| Cache-Aside    | Write to DB only                  | Check cache; on miss, load from DB     | Eventual           | General purpose, read-heavy       |
| Read-Through   | Write to DB + invalidate cache    | Cache auto-loads on miss (LoadingCache)| Eventual           | Read-heavy, stampede protection   |
| Write-Through  | Write to cache + DB synchronously | Read from cache (always populated)     | Strong             | Read-heavy, consistency-critical  |
| Write-Back     | Write to cache only (mark dirty)  | Read from cache; fallback to DB        | Eventual (delayed) | Write-heavy, counters, analytics  |
| Write-Around   | Write to DB only (bypass cache)   | Check cache; on miss, load from DB     | Eventual           | Write-heavy, infrequent reads     |
| Refresh-Ahead  | Write to cache + DB               | Cache proactively refreshes in background | Eventual        | Read-heavy, predictable access    |

---

## Redis Key Namespaces

| Key Pattern                       | Redis Type | Component     | Description                            |
|-----------------------------------|------------|---------------|----------------------------------------|
| `leaderboard:{boardName}`         | ZSET       | Leaderboard   | Sorted set for a named leaderboard     |
| `cache:lru:data`                  | HASH       | LRU Cache     | Key-value data store                   |
| `cache:lru:access`                | ZSET       | LRU Cache     | Access timestamps (score = epoch ms)   |
| `cache:lfu:data`                  | HASH       | LFU Cache     | Key-value data store                   |
| `cache:lfu:access`                | ZSET       | LFU Cache     | Access frequencies (score = count)     |
| `pattern:{patternName}:cache`     | HASH       | Caching Patterns | L2 cache entries for each pattern   |
| `pattern:{patternName}:meta`      | SET/HASH   | Caching Patterns | Pattern metadata (e.g., dirty keys) |

---

## Build Commands

```bash
# Build (includes checkstyle validation at the validate phase)
./mvnw clean compile

# Run the application
./mvnw spring-boot:run

# Run tests
./mvnw test

# Checkstyle only
./mvnw checkstyle:check

# Package as executable JAR (skip tests)
./mvnw package -DskipTests

# Run the packaged JAR
java -jar target/redis-sorted-set-0.0.1-SNAPSHOT.jar
```

---

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Ensure checkstyle passes: `./mvnw checkstyle:check`
5. Commit with a descriptive message: `git commit -m "Add my feature"`
6. Push to your fork: `git push origin feature/my-feature`
7. Open a Pull Request

### Code Style

Code style is enforced by Checkstyle (config in `checkstyle.xml`, runs automatically during Maven `validate` phase):

- Google Java Style base with relaxations for Lombok
- 150 character line length limit
- 500 line file limit, 60 line method limit
- No star imports (`import java.util.*` is forbidden)
- No tabs (spaces only)
- `camelCase` for members, methods, parameters, and local variables
- `UPPER_SNAKE_CASE` for constants

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

## Acknowledgments

- [Spring Boot](https://spring.io/projects/spring-boot) -- Application framework
- [Redis](https://redis.io/) -- In-memory data store
- [Caffeine](https://github.com/ben-manes/caffeine) -- High-performance Java caching library
- [Aiven](https://aiven.io/) -- Managed cloud database services
- [springdoc-openapi](https://springdoc.org/) -- OpenAPI 3 / Swagger UI integration
- [Lombok](https://projectlombok.org/) -- Java boilerplate reduction
