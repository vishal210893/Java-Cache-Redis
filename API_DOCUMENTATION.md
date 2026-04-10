# Redis Caching Project - API Documentation

Base URL: `http://localhost:8081`

Swagger UI: `http://localhost:8081/swagger-ui.html`

---

## 1. Leaderboard API (Redis Sorted Set Operations)

All endpoints use path variable `{boardName}` to support multiple independent leaderboards.

---

### 1.1 ZADD — Add a Member with Score

Adds a member to the sorted set. If the member already exists, its score is updated.

```
POST http://localhost:8081/api/leaderboards/gaming/members
Content-Type: application/json

{
  "memberId": "alice",
  "score": 2500
}
```

**Postman Setup:**
- Method: `POST`
- URL: `http://localhost:8081/api/leaderboards/gaming/members`
- Body → raw → JSON:
```json
{
  "memberId": "alice",
  "score": 2500
}
```

**Try adding more members:**
```json
{"memberId": "bob", "score": 1800}
{"memberId": "charlie", "score": 3200}
{"memberId": "diana", "score": 2900}
{"memberId": "eve", "score": 1500}
{"memberId": "frank", "score": 4100}
```

**Redis Command Equivalent:** `ZADD leaderboard:gaming 2500 alice`

---

### 1.2 ZSCORE + ZREVRANK — Get Member Details

Returns the member's score and rank (0 = highest score).

```
GET http://localhost:8081/api/leaderboards/gaming/members/alice
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/leaderboards/gaming/members/alice`

**Expected Response:**
```json
{
  "success": true,
  "data": {
    "memberId": "alice",
    "score": 2500.0,
    "rank": 3
  },
  "timestamp": "2026-04-09T14:30:00.000"
}
```

**Redis Command Equivalent:** `ZSCORE leaderboard:gaming alice` + `ZREVRANK leaderboard:gaming alice`

---

### 1.3 ZREVRANGE — Get Top N Members

Returns the top N members sorted by highest score first.

```
GET http://localhost:8081/api/leaderboards/gaming/top?count=3
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/leaderboards/gaming/top`
- Params: `count` = `3`

**Expected Response:**
```json
{
  "success": true,
  "data": [
    {"memberId": "frank", "score": 4100.0, "rank": 0},
    {"memberId": "charlie", "score": 3200.0, "rank": 1},
    {"memberId": "diana", "score": 2900.0, "rank": 2}
  ]
}
```

**Redis Command Equivalent:** `ZREVRANGE leaderboard:gaming 0 2 WITHSCORES`

---

### 1.4 ZREVRANGE — Get Members by Rank Range

Returns members within a rank range (0-indexed, highest score first).

```
GET http://localhost:8081/api/leaderboards/gaming/rank-range?start=2&end=4
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/leaderboards/gaming/rank-range`
- Params: `start` = `2`, `end` = `4`

**Redis Command Equivalent:** `ZREVRANGE leaderboard:gaming 2 4 WITHSCORES`

---

### 1.5 ZRANGEBYSCORE — Get Members by Score Range

Returns members whose scores fall within [min, max], sorted low to high.

```
GET http://localhost:8081/api/leaderboards/gaming/score-range?min=2000&max=3500
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/leaderboards/gaming/score-range`
- Params: `min` = `2000`, `max` = `3500`

**Redis Command Equivalent:** `ZRANGEBYSCORE leaderboard:gaming 2000 3500 WITHSCORES`

---

### 1.6 ZINCRBY — Increment a Member's Score

Atomically increments the score of a member. Works even if the member doesn't exist yet (creates with the delta as initial score).

```
PATCH http://localhost:8081/api/leaderboards/gaming/members/alice/score?delta=500
```

**Postman Setup:**
- Method: `PATCH`
- URL: `http://localhost:8081/api/leaderboards/gaming/members/alice/score`
- Params: `delta` = `500`

**Expected Response:**
```json
{
  "success": true,
  "message": "Score incremented",
  "data": 3000.0
}
```

**Redis Command Equivalent:** `ZINCRBY leaderboard:gaming 500 alice`

---

### 1.7 ZREM — Remove a Member

Removes a member from the sorted set.

```
DELETE http://localhost:8081/api/leaderboards/gaming/members/eve
```

**Postman Setup:**
- Method: `DELETE`
- URL: `http://localhost:8081/api/leaderboards/gaming/members/eve`

**Redis Command Equivalent:** `ZREM leaderboard:gaming eve`

---

### 1.8 ZCARD — Get Board Size

Returns the total number of members in the sorted set.

```
GET http://localhost:8081/api/leaderboards/gaming/size
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/leaderboards/gaming/size`

**Redis Command Equivalent:** `ZCARD leaderboard:gaming`

---

### 1.9 ZCOUNT — Count Members in Score Range

Returns how many members have scores within [min, max].

```
GET http://localhost:8081/api/leaderboards/gaming/count?min=2000&max=3000
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/leaderboards/gaming/count`
- Params: `min` = `2000`, `max` = `3000`

**Redis Command Equivalent:** `ZCOUNT leaderboard:gaming 2000 3000`

---

## 2. LRU Cache API (Least Recently Used)

**How it works:** Uses a Redis sorted set where the **score = access timestamp (epoch ms)**. On eviction, the member with the **lowest timestamp** (oldest access) is removed. Capacity is 5 by default.

**Redis keys used:**
- `cache:lru:access` — sorted set (key → timestamp)
- `cache:lru:data` — hash (key → value)

---

### 2.1 Put a Key-Value Pair

Inserts into the cache. If cache is full (5 items), evicts the least recently used entry.

```
POST http://localhost:8081/api/cache/lru/entries
Content-Type: application/json

{
  "key": "user1",
  "value": "Alice"
}
```

**Postman Setup:**
- Method: `POST`
- URL: `http://localhost:8081/api/cache/lru/entries`
- Body → raw → JSON:
```json
{
  "key": "user1",
  "value": "Alice"
}
```

**Try this sequence to see eviction:**
```json
{"key": "user1", "value": "Alice"}
{"key": "user2", "value": "Bob"}
{"key": "user3", "value": "Charlie"}
{"key": "user4", "value": "Diana"}
{"key": "user5", "value": "Eve"}
```
Cache is now full (5/5). Now access user1 to refresh its timestamp:
```
GET http://localhost:8081/api/cache/lru/entries/user1
```
Now add user6 — this evicts user2 (oldest access, not user1 because we just accessed it):
```json
POST http://localhost:8081/api/cache/lru/entries
{"key": "user6", "value": "Frank"}
```

**Redis Commands Equivalent:**
- `ZADD cache:lru:access <timestamp> user1`
- `HSET cache:lru:data user1 Alice`
- Eviction: `ZRANGE cache:lru:access 0 0` → `ZREM` + `HDEL`

---

### 2.2 Get a Value by Key

Returns the cached value. **Updates the access timestamp** (this is what makes it LRU).

```
GET http://localhost:8081/api/cache/lru/entries/user1
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/cache/lru/entries/user1`

**Cache HIT response:**
```json
{
  "success": true,
  "message": "Cache HIT",
  "data": "Alice"
}
```

**Cache MISS response:**
```json
{
  "success": true,
  "message": "Cache MISS",
  "data": null
}
```

**Redis Commands Equivalent:** `HGET cache:lru:data user1` + `ZADD cache:lru:access <new_timestamp> user1`

---

### 2.3 View Cache State

Shows all entries sorted by access time (oldest first = next eviction candidate).

```
GET http://localhost:8081/api/cache/lru/state
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/cache/lru/state`

**Expected Response:**
```json
{
  "success": true,
  "data": {
    "policy": "LRU",
    "capacity": 5,
    "currentSize": 5,
    "entries": [
      {"key": "user4", "value": "Diana", "score": 1775724466610.0},
      {"key": "user5", "value": "Eve", "score": 1775724466795.0},
      {"key": "user1", "value": "Alice", "score": 1775724466855.0},
      {"key": "user6", "value": "Frank", "score": 1775724466909.0},
      {"key": "user7", "value": "Grace", "score": 1775724466962.0}
    ],
    "evictionOrder": "Lowest timestamp (oldest access) evicted first — ZRANGE index 0"
  }
}
```

The `score` field is the epoch millisecond timestamp. First entry = next to be evicted.

---

### 2.4 Clear the LRU Cache

Removes all entries from the LRU cache.

```
DELETE http://localhost:8081/api/cache/lru
```

**Postman Setup:**
- Method: `DELETE`
- URL: `http://localhost:8081/api/cache/lru`

---

## 3. LFU Cache API (Least Frequently Used)

**How it works:** Uses a Redis sorted set where the **score = access frequency count**. On eviction, the member with the **lowest frequency** (least accessed) is removed. Capacity is 5 by default.

**Redis keys used:**
- `cache:lfu:frequency` — sorted set (key → access count)
- `cache:lfu:data` — hash (key → value)

---

### 3.1 Put a Key-Value Pair

Inserts into the cache with initial frequency=1. If key exists, increments frequency. If full, evicts the least frequently used entry.

```
POST http://localhost:8081/api/cache/lfu/entries
Content-Type: application/json

{
  "key": "laptop",
  "value": "MacBook Pro 16"
}
```

**Postman Setup:**
- Method: `POST`
- URL: `http://localhost:8081/api/cache/lfu/entries`
- Body → raw → JSON:
```json
{
  "key": "laptop",
  "value": "MacBook Pro 16"
}
```

**Try this sequence to see frequency-based eviction:**
```json
{"key": "laptop", "value": "MacBook Pro 16"}
{"key": "phone", "value": "iPhone 16"}
{"key": "tablet", "value": "iPad Air"}
{"key": "watch", "value": "Apple Watch Ultra"}
{"key": "headphones", "value": "AirPods Max"}
```
Cache is full. Now access laptop four times to boost its frequency:
```
GET http://localhost:8081/api/cache/lfu/entries/laptop
GET http://localhost:8081/api/cache/lfu/entries/laptop
GET http://localhost:8081/api/cache/lfu/entries/laptop
GET http://localhost:8081/api/cache/lfu/entries/laptop
```
Access phone twice:
```
GET http://localhost:8081/api/cache/lfu/entries/phone
GET http://localhost:8081/api/cache/lfu/entries/phone
```
Now add speaker — evicts tablet, watch, or headphones (all have frequency=1, the lowest):
```json
POST http://localhost:8081/api/cache/lfu/entries
{"key": "speaker", "value": "HomePod"}
```

**Redis Commands Equivalent:**
- New key: `ZADD cache:lfu:frequency 1 laptop` + `HSET cache:lfu:data laptop "MacBook Pro 16"`
- Existing key: `ZINCRBY cache:lfu:frequency 1 laptop`
- Eviction: `ZRANGE cache:lfu:frequency 0 0` → `ZREM` + `HDEL`

---

### 3.2 Get a Value by Key

Returns the cached value. **Increments the access frequency** (this is what makes it LFU).

```
GET http://localhost:8081/api/cache/lfu/entries/laptop
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/cache/lfu/entries/laptop`

**Redis Command Equivalent:** `HGET cache:lfu:data laptop` + `ZINCRBY cache:lfu:frequency 1 laptop`

---

### 3.3 View Cache State

Shows all entries sorted by access frequency (lowest first = next eviction candidate).

```
GET http://localhost:8081/api/cache/lfu/state
```

**Postman Setup:**
- Method: `GET`
- URL: `http://localhost:8081/api/cache/lfu/state`

**Expected Response:**
```json
{
  "success": true,
  "data": {
    "policy": "LFU",
    "capacity": 5,
    "currentSize": 5,
    "entries": [
      {"key": "headphones", "value": "AirPods Max", "score": 1.0},
      {"key": "speaker", "value": "HomePod", "score": 1.0},
      {"key": "tablet", "value": "iPad Air", "score": 2.0},
      {"key": "phone", "value": "iPhone 16", "score": 3.0},
      {"key": "laptop", "value": "MacBook Pro 16", "score": 5.0}
    ],
    "evictionOrder": "Lowest frequency evicted first — ZRANGE index 0 (ZINCRBY on access)"
  }
}
```

The `score` field is the access count. First entry = next to be evicted.

---

### 3.4 Clear the LFU Cache

Removes all entries from the LFU cache.

```
DELETE http://localhost:8081/api/cache/lfu
```

**Postman Setup:**
- Method: `DELETE`
- URL: `http://localhost:8081/api/cache/lfu`

---

## 4. Cache-Aside Pattern (Lazy Loading)

**How it works:** Application manages the cache explicitly. On read, checks L1 (Caffeine) -> L2 (Redis) -> DB (PostgreSQL). On write, updates DB first, then invalidates both cache layers so stale data is never served.

**Base path:** `/api/cache/patterns/cache-aside`

**Cache layers:**
- L1: Caffeine (in-process, ~microsecond access)
- L2: Redis hash `pattern:cache-aside:cache`
- DB: PostgreSQL `cache_products` table (partitioned by `patternName = cache-aside`)

---

### 4.1 Write Entry

Writes to DB first, then invalidates L1 + L2 cache. Next read will lazy-load from DB.

```
POST http://localhost:8081/api/cache/patterns/cache-aside/entries
Content-Type: application/json

{
  "key": "laptop",
  "value": "MacBook Pro M4"
}
```

### 4.2 Read Entry

Checks L1 -> L2 -> DB. On miss, loads from DB and populates cache layers.

```
GET http://localhost:8081/api/cache/patterns/cache-aside/entries/laptop
```

**Cache MISS response (first read after write/seed):**
```json
{
  "success": true,
  "message": "Source: DATABASE (cache miss - loaded from DB, cached for next read)",
  "data": "MacBook Pro M4"
}
```

**Cache HIT response (subsequent reads):**
```json
{
  "success": true,
  "message": "Source: L1_CAFFEINE",
  "data": "MacBook Pro M4"
}
```

### 4.3 View State

Returns full state including L1 entries, L2 entries, DB entries, metadata, and an ASCII diagram of the architecture.

```
GET http://localhost:8081/api/cache/patterns/cache-aside/state
```

### 4.4 Clear Cache

Clears L1 + L2 cache. DB data is preserved.

```
DELETE http://localhost:8081/api/cache/patterns/cache-aside
```

### 4.5 Seed Database

Seeds PostgreSQL with test data. Does not populate cache.

```
POST http://localhost:8081/api/cache/patterns/cache-aside/db/seed
Content-Type: application/json

{
  "entries": {
    "laptop": "MacBook Pro M4",
    "phone": "iPhone 16 Pro",
    "tablet": "iPad Air M2",
    "watch": "Apple Watch Ultra",
    "headphones": "AirPods Max"
  }
}
```

### 4.6 View DB State

Shows raw PostgreSQL contents for this pattern.

```
GET http://localhost:8081/api/cache/patterns/cache-aside/db/state
```

### Recommended Postman Flow

1. **Clear cache** — `DELETE /api/cache/patterns/cache-aside`
2. **Seed DB** — `POST /db/seed` with test data
3. **Read laptop** — `GET /entries/laptop` → MISS, loaded from DB, cached
4. **Read laptop again** — `GET /entries/laptop` → HIT from L1 (Caffeine)
5. **Write entry** — `POST /entries` with `{"key":"laptop","value":"MacBook Pro M4 Max"}` → DB updated, cache invalidated
6. **Read after write** — `GET /entries/laptop` → MISS again (cache was invalidated), loads new value from DB
7. **View state** — `GET /state` → see all layers
8. **View DB state** — `GET /db/state` → see PostgreSQL contents

---

## 5. Read-Through Pattern

**How it works:** Similar to cache-aside but the cache itself handles misses via Caffeine's `LoadingCache`. On miss, the loading function automatically fetches from L2 (Redis) -> DB. Provides stampede protection (only one thread loads a given key).

**Base path:** `/api/cache/patterns/read-through`

**Cache layers:**
- L1: Caffeine LoadingCache (auto-loads on miss with stampede protection)
- L2: Redis hash `pattern:read-through:cache`
- DB: PostgreSQL `cache_products` table (partitioned by `patternName = read-through`)

---

### 5.1 Write Entry

Writes to DB, then invalidates L1 + L2 cache.

```
POST http://localhost:8081/api/cache/patterns/read-through/entries
Content-Type: application/json

{
  "key": "laptop",
  "value": "MacBook Pro M4"
}
```

### 5.2 Read Entry

Caffeine LoadingCache handles the read. On miss, the loader fetches from Redis -> DB automatically.

```
GET http://localhost:8081/api/cache/patterns/read-through/entries/laptop
```

### 5.3 View State

```
GET http://localhost:8081/api/cache/patterns/read-through/state
```

### 5.4 Clear Cache

```
DELETE http://localhost:8081/api/cache/patterns/read-through
```

### 5.5 Seed Database

```
POST http://localhost:8081/api/cache/patterns/read-through/db/seed
Content-Type: application/json

{
  "entries": {
    "laptop": "MacBook Pro M4",
    "phone": "iPhone 16 Pro",
    "tablet": "iPad Air M2",
    "watch": "Apple Watch Ultra",
    "headphones": "AirPods Max"
  }
}
```

### 5.6 View DB State

```
GET http://localhost:8081/api/cache/patterns/read-through/db/state
```

### Recommended Postman Flow

1. **Clear cache** — `DELETE /api/cache/patterns/read-through`
2. **Seed DB** — `POST /db/seed` with test data
3. **Read laptop** — `GET /entries/laptop` → LoadingCache auto-fetches from DB
4. **Read laptop again** — `GET /entries/laptop` → L1 HIT (LoadingCache already has it)
5. **Write entry** — `POST /entries` with new value → invalidates cache
6. **Read after write** — `GET /entries/laptop` → loader re-fetches new value
7. **View state** — `GET /state`
8. **View DB state** — `GET /db/state`

---

## 6. Write-Through Pattern

**How it works:** On write, data is synchronously written to DB, then L2 (Redis), then L1 (Caffeine) before returning success. Guarantees that cache is always in sync with DB. Read path is L1 -> L2 -> DB.

**Base path:** `/api/cache/patterns/write-through`

**Cache layers:**
- L1: Caffeine (populated on write)
- L2: Redis hash `pattern:write-through:cache` (populated on write)
- DB: PostgreSQL `cache_products` table (partitioned by `patternName = write-through`)

---

### 6.1 Write Entry

Writes to DB (sync) -> L2 (Redis) -> L1 (Caffeine) -> returns confirmation. All layers updated atomically.

```
POST http://localhost:8081/api/cache/patterns/write-through/entries
Content-Type: application/json

{
  "key": "laptop",
  "value": "MacBook Pro M4"
}
```

### 6.2 Read Entry

```
GET http://localhost:8081/api/cache/patterns/write-through/entries/laptop
```

**Expected:** Immediate HIT from L1 (Caffeine) since write-through populated all layers.

### 6.3 View State

```
GET http://localhost:8081/api/cache/patterns/write-through/state
```

### 6.4 Clear Cache

```
DELETE http://localhost:8081/api/cache/patterns/write-through
```

### 6.5 Seed Database

```
POST http://localhost:8081/api/cache/patterns/write-through/db/seed
Content-Type: application/json

{
  "entries": {
    "laptop": "MacBook Pro M4",
    "phone": "iPhone 16 Pro",
    "tablet": "iPad Air M2",
    "watch": "Apple Watch Ultra",
    "headphones": "AirPods Max"
  }
}
```

### 6.6 View DB State

```
GET http://localhost:8081/api/cache/patterns/write-through/db/state
```

### Recommended Postman Flow

1. **Clear cache** — `DELETE /api/cache/patterns/write-through`
2. **Seed DB** — `POST /db/seed` with test data
3. **Write entry** — `POST /entries` with `{"key":"laptop","value":"MacBook Pro M4"}` → all 3 layers updated
4. **Read entry** — `GET /entries/laptop` → immediate L1 HIT
5. **View state** — `GET /state` → see all layers populated
6. **View DB state** — `GET /db/state` → confirm DB has the entry

---

## 7. Write-Back Pattern (Write-Behind)

**How it works:** On write, data goes to L1 (Caffeine) + L2 (Redis) only (~1ms latency) and the key is marked dirty. Dirty keys are flushed to DB either manually (via flush endpoint) or by a background process. Provides the fastest write performance at the cost of potential data loss if the process crashes before flush.

**Base path:** `/api/cache/patterns/write-back`

**Cache layers:**
- L1: Caffeine (immediate write)
- L2: Redis hash `pattern:write-back:cache` (immediate write)
- DB: PostgreSQL `cache_products` table (deferred write via flush)
- Dirty tracking: Redis set `pattern:write-back:meta` tracks unflushed keys

---

### 7.1 Write Entry

Writes to L1 + L2 only. Key is marked dirty. DB is NOT updated.

```
POST http://localhost:8081/api/cache/patterns/write-back/entries
Content-Type: application/json

{
  "key": "laptop",
  "value": "MacBook Pro M4"
}
```

### 7.2 Read Entry

```
GET http://localhost:8081/api/cache/patterns/write-back/entries/laptop
```

### 7.3 Flush Dirty Keys to DB

Manually flushes all dirty keys from cache to PostgreSQL.

```
POST http://localhost:8081/api/cache/patterns/write-back/flush
```

### 7.4 View State

```
GET http://localhost:8081/api/cache/patterns/write-back/state
```

### 7.5 Clear Cache

```
DELETE http://localhost:8081/api/cache/patterns/write-back
```

### 7.6 Seed Database

```
POST http://localhost:8081/api/cache/patterns/write-back/db/seed
Content-Type: application/json

{
  "entries": {
    "laptop": "MacBook Pro M4",
    "phone": "iPhone 16 Pro",
    "tablet": "iPad Air M2",
    "watch": "Apple Watch Ultra",
    "headphones": "AirPods Max"
  }
}
```

### 7.7 View DB State

```
GET http://localhost:8081/api/cache/patterns/write-back/db/state
```

### Recommended Postman Flow

1. **Clear cache** — `DELETE /api/cache/patterns/write-back`
2. **Seed DB** — `POST /db/seed` with test data
3. **Write entries** — `POST /entries` with several items (goes to cache only, ~1ms)
4. **View DB state** — `GET /db/state` → DB does NOT have the written entries yet (only seeded data)
5. **View state** — `GET /state` → see dirty keys listed in metadata
6. **Flush** — `POST /flush` → dirty keys written to DB
7. **View DB state** — `GET /db/state` → DB now has the flushed entries
8. **View state** — `GET /state` → dirty set is now empty

---

## 8. Write-Around Pattern

**How it works:** On write, data goes directly to DB, and L1 + L2 cache are invalidated. The cache is completely bypassed on write. On subsequent reads, data is loaded from DB into cache (lazy load, same as cache-aside reads).

**Base path:** `/api/cache/patterns/write-around`

**Cache layers:**
- L1: Caffeine (populated only on read)
- L2: Redis hash `pattern:write-around:cache` (populated only on read)
- DB: PostgreSQL `cache_products` table (written directly)

---

### 8.1 Write Entry

Writes to DB only. L1 + L2 caches are invalidated (not populated).

```
POST http://localhost:8081/api/cache/patterns/write-around/entries
Content-Type: application/json

{
  "key": "laptop",
  "value": "MacBook Pro M4"
}
```

### 8.2 Read Entry

```
GET http://localhost:8081/api/cache/patterns/write-around/entries/laptop
```

**First read:** MISS → loads from DB → populates cache
**Second read:** HIT from L1 (Caffeine)

### 8.3 View State

```
GET http://localhost:8081/api/cache/patterns/write-around/state
```

### 8.4 Clear Cache

```
DELETE http://localhost:8081/api/cache/patterns/write-around
```

### 8.5 Seed Database

```
POST http://localhost:8081/api/cache/patterns/write-around/db/seed
Content-Type: application/json

{
  "entries": {
    "laptop": "MacBook Pro M4",
    "phone": "iPhone 16 Pro",
    "tablet": "iPad Air M2",
    "watch": "Apple Watch Ultra",
    "headphones": "AirPods Max"
  }
}
```

### 8.6 View DB State

```
GET http://localhost:8081/api/cache/patterns/write-around/db/state
```

### Recommended Postman Flow

1. **Clear cache** — `DELETE /api/cache/patterns/write-around`
2. **Seed DB** — `POST /db/seed` with test data
3. **Write entry** — `POST /entries` with `{"key":"laptop","value":"MacBook Pro M4"}` → DB only, cache invalidated
4. **Read laptop** — `GET /entries/laptop` → MISS, loads from DB, cached
5. **Read laptop again** — `GET /entries/laptop` → HIT from L1
6. **View state** — `GET /state`
7. **View DB state** — `GET /db/state`

---

## 9. Refresh-Ahead Pattern

**How it works:** Uses Caffeine LoadingCache with TTL-based expiry. Entries are proactively refreshed in the background during the last 20% of their TTL window. This ensures frequently-accessed keys are always fresh without blocking the read path.

**Base path:** `/api/cache/patterns/refresh-ahead`

**Cache layers:**
- L1: Caffeine LoadingCache (TTL-managed with async refresh)
- L2: Redis hash `pattern:refresh-ahead:cache`
- DB: PostgreSQL `cache_products` table (partitioned by `patternName = refresh-ahead`)

---

### 9.1 Write Entry

Writes to DB + L2 (Redis) + L1 (Caffeine LoadingCache).

```
POST http://localhost:8081/api/cache/patterns/refresh-ahead/entries
Content-Type: application/json

{
  "key": "laptop",
  "value": "MacBook Pro M4"
}
```

### 9.2 Read Entry

Caffeine manages the read. If entry is in the last 20% of TTL, an async background refresh is triggered while the stale value is returned immediately.

```
GET http://localhost:8081/api/cache/patterns/refresh-ahead/entries/laptop
```

### 9.3 View State

Returns cache state including TTL information and refresh metadata.

```
GET http://localhost:8081/api/cache/patterns/refresh-ahead/state
```

### 9.4 Clear Cache

```
DELETE http://localhost:8081/api/cache/patterns/refresh-ahead
```

### 9.5 Seed Database

```
POST http://localhost:8081/api/cache/patterns/refresh-ahead/db/seed
Content-Type: application/json

{
  "entries": {
    "laptop": "MacBook Pro M4",
    "phone": "iPhone 16 Pro",
    "tablet": "iPad Air M2",
    "watch": "Apple Watch Ultra",
    "headphones": "AirPods Max"
  }
}
```

### 9.6 View DB State

```
GET http://localhost:8081/api/cache/patterns/refresh-ahead/db/state
```

### Recommended Postman Flow

1. **Clear cache** — `DELETE /api/cache/patterns/refresh-ahead`
2. **Seed DB** — `POST /db/seed` with test data
3. **Write entry** — `POST /entries` with `{"key":"laptop","value":"MacBook Pro M4"}` → all layers populated
4. **Read entry** — `GET /entries/laptop` → HIT
5. **View state** — `GET /state` → check TTL info and refresh metadata
6. **View DB state** — `GET /db/state`

---

## Quick Reference: All Endpoints

### Leaderboard

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/leaderboards/{board}/members` | POST | Add member with score |
| `/api/leaderboards/{board}/members/{id}` | GET | Get member score + rank |
| `/api/leaderboards/{board}/top?count=N` | GET | Top N members |
| `/api/leaderboards/{board}/rank-range?start=S&end=E` | GET | Members by rank range |
| `/api/leaderboards/{board}/score-range?min=X&max=Y` | GET | Members by score range |
| `/api/leaderboards/{board}/members/{id}/score?delta=D` | PATCH | Increment score |
| `/api/leaderboards/{board}/members/{id}` | DELETE | Remove member |
| `/api/leaderboards/{board}/size` | GET | Board size |
| `/api/leaderboards/{board}/count?min=X&max=Y` | GET | Count in score range |

### LRU / LFU Cache

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/cache/lru/entries` | POST | Put key-value |
| `/api/cache/lru/entries/{key}` | GET | Get value (updates timestamp) |
| `/api/cache/lru/state` | GET | View cache state |
| `/api/cache/lru` | DELETE | Clear cache |
| `/api/cache/lfu/entries` | POST | Put key-value |
| `/api/cache/lfu/entries/{key}` | GET | Get value (increments frequency) |
| `/api/cache/lfu/state` | GET | View cache state |
| `/api/cache/lfu` | DELETE | Clear cache |

### Caching Patterns (shared endpoints for all 6 patterns)

Replace `{pattern}` with: `cache-aside`, `read-through`, `write-through`, `write-back`, `write-around`, `refresh-ahead`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/cache/patterns/{pattern}/entries` | POST | Write entry |
| `/api/cache/patterns/{pattern}/entries/{key}` | GET | Read entry (L1 -> L2 -> DB) |
| `/api/cache/patterns/{pattern}/state` | GET | Full state (cache + DB + metadata + diagram) |
| `/api/cache/patterns/{pattern}` | DELETE | Clear cache (DB preserved) |
| `/api/cache/patterns/{pattern}/db/seed` | POST | Seed PostgreSQL with test data |
| `/api/cache/patterns/{pattern}/db/state` | GET | View raw PostgreSQL contents |
| `/api/cache/patterns/write-back/flush` | POST | Flush dirty keys to DB (write-back only) |

### Caching Pattern Comparison

| Pattern | Write Behavior | Read Behavior | Best For |
|---------|---------------|---------------|----------|
| Cache-Aside | DB first, invalidate cache | L1->L2->DB (lazy load) | General purpose, read-heavy |
| Read-Through | DB first, invalidate cache | LoadingCache auto-fetches | Stampede protection |
| Write-Through | DB->L2->L1 (sync all layers) | L1->L2->DB | Strong consistency |
| Write-Back | L1+L2 only, defer DB write | L1->L2->DB | Write-heavy, low latency |
| Write-Around | DB only, invalidate cache | L1->L2->DB (lazy load) | Write-heavy, infrequent reads |
| Refresh-Ahead | DB+L2+L1 (LoadingCache) | TTL-managed + async refresh | Low-latency reads, predictable access |

---

## Redis Key Namespaces

| Key Pattern | Type | Used By |
|-------------|------|---------|
| `leaderboard:{boardName}` | Sorted Set | Leaderboard API |
| `cache:lru:access` | Sorted Set | LRU Cache (score = timestamp) |
| `cache:lru:data` | Hash | LRU Cache (key-value store) |
| `cache:lfu:frequency` | Sorted Set | LFU Cache (score = access count) |
| `cache:lfu:data` | Hash | LFU Cache (key-value store) |
| `pattern:cache-aside:cache` | Hash | Cache-Aside L2 |
| `pattern:read-through:cache` | Hash | Read-Through L2 |
| `pattern:write-through:cache` | Hash | Write-Through L2 |
| `pattern:write-back:cache` | Hash | Write-Back L2 |
| `pattern:write-back:meta` | Set | Write-Back dirty key tracking |
| `pattern:write-around:cache` | Hash | Write-Around L2 |
| `pattern:refresh-ahead:cache` | Hash | Refresh-Ahead L2 |

---

## Redis Insight: What to Look For

After running the endpoints, open Redis Insight and inspect:

| Redis Key | Type | Observe |
|-----------|------|---------|
| `leaderboard:gaming` | Sorted Set | Members sorted by score. Click to see rank visualization. |
| `cache:lru:access` | Sorted Set | Score = epoch ms timestamp. Lowest = next eviction. |
| `cache:lru:data` | Hash | Key-value pairs backing the LRU cache. |
| `cache:lfu:frequency` | Sorted Set | Score = access count. Lowest frequency = next eviction. |
| `cache:lfu:data` | Hash | Key-value pairs backing the LFU cache. |
| `pattern:cache-aside:cache` | Hash | L2 cache entries for cache-aside pattern. |
| `pattern:read-through:cache` | Hash | L2 cache entries for read-through pattern. |
| `pattern:write-through:cache` | Hash | L2 cache entries — populated on write. |
| `pattern:write-back:cache` | Hash | L2 cache entries — written immediately, DB deferred. |
| `pattern:write-back:meta` | Set | Dirty keys waiting to be flushed to DB. |
| `pattern:write-around:cache` | Hash | L2 cache entries — populated only on read. |
| `pattern:refresh-ahead:cache` | Hash | L2 cache entries with TTL-based refresh. |
