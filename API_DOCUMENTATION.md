# Redis Sorted Set Learning - API Documentation

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

## Quick Reference: Redis Sorted Set Commands Mapped to Endpoints

| Redis Command    | What It Does                        | API Endpoint                                         | HTTP Method |
|------------------|-------------------------------------|------------------------------------------------------|-------------|
| `ZADD`           | Add member with score               | `/api/leaderboards/{board}/members`                  | POST        |
| `ZSCORE`         | Get member's score                  | `/api/leaderboards/{board}/members/{id}`              | GET         |
| `ZREVRANK`       | Get member's rank (highest first)   | `/api/leaderboards/{board}/members/{id}`              | GET         |
| `ZREVRANGE`      | Get members by rank (highest first) | `/api/leaderboards/{board}/top?count=N`               | GET         |
| `ZREVRANGE`      | Get members in rank range           | `/api/leaderboards/{board}/rank-range?start=S&end=E`  | GET         |
| `ZRANGEBYSCORE`  | Get members in score range          | `/api/leaderboards/{board}/score-range?min=X&max=Y`   | GET         |
| `ZINCRBY`        | Increment member's score            | `/api/leaderboards/{board}/members/{id}/score?delta=D` | PATCH       |
| `ZREM`           | Remove a member                     | `/api/leaderboards/{board}/members/{id}`              | DELETE      |
| `ZCARD`          | Count total members                 | `/api/leaderboards/{board}/size`                      | GET         |
| `ZCOUNT`         | Count members in score range        | `/api/leaderboards/{board}/count?min=X&max=Y`         | GET         |

---

## Redis Insight: What to Look For

After running the endpoints, open Redis Insight and inspect:

| Redis Key              | Type       | Observe                                                    |
|------------------------|------------|------------------------------------------------------------|
| `leaderboard:gaming`   | Sorted Set | Members sorted by score. Click to see rank visualization.  |
| `cache:lru:access`     | Sorted Set | Score = epoch ms timestamp. Lowest = next eviction.        |
| `cache:lru:data`       | Hash       | Key-value pairs backing the LRU cache.                     |
| `cache:lfu:frequency`  | Sorted Set | Score = access count. Lowest frequency = next eviction.    |
| `cache:lfu:data`       | Hash       | Key-value pairs backing the LFU cache.                     |
