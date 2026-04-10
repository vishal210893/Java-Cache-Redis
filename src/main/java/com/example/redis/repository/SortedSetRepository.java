package com.example.redis.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * <b>Sorted Set Repository</b>
 *
 * <p>Thin wrapper around {@link RedisTemplate} sorted set (ZSET) operations. Each method maps 1:1 to a Redis
 * command, adding debug logging that mirrors Redis CLI syntax for easy tracing.
 *
 * <pre>
 *  Method                  Redis Command
 *  ──────────────────────  ──────────────────────────────
 *  add()                   ZADD key score member
 *  score()                 ZSCORE key member
 *  rank()                  ZRANK key member
 *  reverseRank()           ZREVRANK key member
 *  rangeWithScores()       ZRANGE key start end WITHSCORES
 *  reverseRangeWithScores()ZREVRANGE key start end WITHSCORES
 *  rangeByScoreWithScores()ZRANGEBYSCORE key min max WITHSCORES
 *  incrementScore()        ZINCRBY key delta member
 *  remove()                ZREM key member [member...]
 *  size()                  ZCARD key
 *  count()                 ZCOUNT key min max
 * </pre>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SortedSetRepository {

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Adds a member with the given score ({@code ZADD}).
     *
     * @param key    the sorted set key
     * @param member the member value
     * @param score  the score to assign
     * @return {@code true} if the member was newly added, {@code false} if updated
     */
    public Boolean add(String key, String member, double score) {
        log.debug("ZADD {} {} {}", key, member, score);
        return redisTemplate.opsForZSet().add(key, member, score);
    }

    /**
     * Returns the score of a member ({@code ZSCORE}).
     *
     * @param key    the sorted set key
     * @param member the member to look up
     * @return the score, or {@code null} if the member does not exist
     */
    public Double score(String key, String member) {
        log.debug("ZSCORE {} {}", key, member);
        return redisTemplate.opsForZSet().score(key, member);
    }

    /**
     * Returns the 0-based rank of a member in ascending order ({@code ZRANK}).
     *
     * @param key    the sorted set key
     * @param member the member to look up
     * @return the rank, or {@code null} if the member does not exist
     */
    public Long rank(String key, String member) {
        log.debug("ZRANK {} {}", key, member);
        return redisTemplate.opsForZSet().rank(key, member);
    }

    /**
     * Returns the 0-based rank of a member in descending order ({@code ZREVRANK}).
     *
     * @param key    the sorted set key
     * @param member the member to look up
     * @return the reverse rank, or {@code null} if the member does not exist
     */
    public Long reverseRank(String key, String member) {
        log.debug("ZREVRANK {} {}", key, member);
        return redisTemplate.opsForZSet().reverseRank(key, member);
    }

    /**
     * Returns members in the given rank range with scores, ascending ({@code ZRANGE ... WITHSCORES}).
     *
     * @param key   the sorted set key
     * @param start start rank (inclusive)
     * @param end   end rank (inclusive)
     * @return set of member-score tuples
     */
    public Set<ZSetOperations.TypedTuple<String>> rangeWithScores(String key, long start, long end) {
        log.debug("ZRANGE {} {} {} WITHSCORES", key, start, end);
        return redisTemplate.opsForZSet().rangeWithScores(key, start, end);
    }

    /**
     * Returns members in the given rank range with scores, descending ({@code ZREVRANGE ... WITHSCORES}).
     *
     * @param key   the sorted set key
     * @param start start rank (inclusive)
     * @param end   end rank (inclusive)
     * @return set of member-score tuples in descending order
     */
    public Set<ZSetOperations.TypedTuple<String>> reverseRangeWithScores(String key, long start, long end) {
        log.debug("ZREVRANGE {} {} {} WITHSCORES", key, start, end);
        return redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
    }

    /**
     * Returns members within a score range with scores ({@code ZRANGEBYSCORE ... WITHSCORES}).
     *
     * @param key the sorted set key
     * @param min minimum score (inclusive)
     * @param max maximum score (inclusive)
     * @return set of member-score tuples within the range
     */
    public Set<ZSetOperations.TypedTuple<String>> rangeByScoreWithScores(String key, double min, double max) {
        log.debug("ZRANGEBYSCORE {} {} {} WITHSCORES", key, min, max);
        return redisTemplate.opsForZSet().rangeByScoreWithScores(key, min, max);
    }

    /**
     * Increments a member's score by the given delta ({@code ZINCRBY}).
     *
     * @param key    the sorted set key
     * @param member the member whose score to increment
     * @param delta  the value to add (can be negative)
     * @return the new score after incrementing
     */
    public Double incrementScore(String key, String member, double delta) {
        log.debug("ZINCRBY {} {} {}", key, delta, member);
        return redisTemplate.opsForZSet().incrementScore(key, member, delta);
    }

    /**
     * Removes one or more members from the sorted set ({@code ZREM}).
     *
     * @param key     the sorted set key
     * @param members the members to remove
     * @return the number of members removed
     */
    public Long remove(String key, String... members) {
        log.debug("ZREM {} {}", key, (Object[]) members);
        return redisTemplate.opsForZSet().remove(key, (Object[]) members);
    }

    /**
     * Returns the cardinality (number of members) of the sorted set ({@code ZCARD}).
     *
     * @param key the sorted set key
     * @return the number of members in the set
     */
    public Long size(String key) {
        log.debug("ZCARD {}", key);
        return redisTemplate.opsForZSet().size(key);
    }

    /**
     * Counts members with scores between {@code min} and {@code max} ({@code ZCOUNT}).
     *
     * @param key the sorted set key
     * @param min minimum score (inclusive)
     * @param max maximum score (inclusive)
     * @return the count of members in the score range
     */
    public Long count(String key, double min, double max) {
        log.debug("ZCOUNT {} {} {}", key, min, max);
        return redisTemplate.opsForZSet().count(key, min, max);
    }
}
