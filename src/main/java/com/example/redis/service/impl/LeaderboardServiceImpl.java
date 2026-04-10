package com.example.redis.service.impl;

import com.example.redis.dto.MemberScoreRequest;
import com.example.redis.dto.RankedMemberResponse;
import com.example.redis.exception.MemberNotFoundException;
import com.example.redis.repository.SortedSetRepository;
import com.example.redis.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Leaderboard Service                                       |
 * +------------------------------------------------------------+
 * |  Demonstrates Redis sorted set (ZSET) operations through   |
 * |  a leaderboard API. Each leaderboard is stored as a single |
 * |  ZSET under {@code leaderboard:{boardName}}. Members are   |
 * |  strings; scores are doubles ranked in descending order    |
 * |  (highest = rank 0).                                       |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Redis layout:
 * <pre>
 *  +----------------------------------------------+
 *  | ZSET  leaderboard:game1                      |
 *  |  { alice:1500, bob:1200, carol:900 }         |
 *  |    ^^ ZREVRANGE gives: alice, bob, carol     |
 *  +----------------------------------------------+
 * </pre>
 *
 * <p>All Redis interaction is delegated to {@link SortedSetRepository}.
 *
 * @see LeaderboardService
 * @see SortedSetRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardServiceImpl implements LeaderboardService {

    private static final String KEY_PREFIX = "leaderboard:";

    private final SortedSetRepository sortedSetRepository;

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Add or Update Member                                      |
     * +------------------------------------------------------------+
     * |  Uses {@code ZADD} to set the member's score. Returns      |
     * |  whether the member was newly added or updated.            |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Redis command flow:
     * <pre>
     *  +---------+  ZADD score member  +------+
     *  |   App   |--------------------&gt;| ZSET |
     *  +---------+                     +------+
     * </pre>
     *
     * @param boardName leaderboard name
     * @param request   member id and score
     * @return {@code true} if the member was newly added; {@code false} if updated
     */
    @Override
    public boolean addMember(String boardName, MemberScoreRequest request) {
        String key = resolveKey(boardName);
        Boolean added = sortedSetRepository.add(key, request.getMemberId(), request.getScore());
        log.info("Added member={} score={} to board={} (new={})",
                request.getMemberId(), request.getScore(), boardName, added);
        return Boolean.TRUE.equals(added);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Get Member Details                                        |
     * +------------------------------------------------------------+
     * |  Retrieves a member's score ({@code ZSCORE}) and           |
     * |  descending rank ({@code ZREVRANK}).                       |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Redis command flow:
     * <pre>
     *  +---------+  ZSCORE   +------+
     *  |   App   |----------&gt;| ZSET |---&gt; score
     *  +---------+           +------+
     *       |
     *       +--- ZREVRANK ---&gt; ZSET ---&gt; descending rank
     * </pre>
     *
     * @param boardName leaderboard name
     * @param memberId  member identifier
     * @return score and rank
     * @throws MemberNotFoundException if the member does not exist on the board
     */
    @Override
    public RankedMemberResponse getMemberDetails(String boardName, String memberId) {
        String key = resolveKey(boardName);
        Double score = sortedSetRepository.score(key, memberId);
        if (score == null) {
            throw new MemberNotFoundException(memberId, boardName);
        }
        Long rank = sortedSetRepository.reverseRank(key, memberId);
        return RankedMemberResponse.builder()
                .memberId(memberId)
                .score(score)
                .rank(rank)
                .build();
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Get Top Members                                           |
     * +------------------------------------------------------------+
     * |  Returns the top N members sorted by score descending      |
     * |  using {@code ZREVRANGE 0 (count-1) WITHSCORES}.           |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Redis command flow:
     * <pre>
     *  +---------+  ZREVRANGE 0 (N-1) WITHSCORES  +------+
     *  |   App   |-------------------------------&gt;| ZSET |
     *  +---------+                                +------+
     * </pre>
     *
     * @param boardName leaderboard name
     * @param count     number of top members to retrieve
     * @return ranked list, highest score first
     */
    @Override
    public List<RankedMemberResponse> getTopMembers(String boardName, int count) {
        String key = resolveKey(boardName);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                sortedSetRepository.reverseRangeWithScores(key, 0, (long) count - 1);
        return toRankedList(tuples, key);
    }

    /**
     * Returns members within the specified descending rank range using
     * {@code ZREVRANGE start end WITHSCORES}.
     *
     * @param boardName leaderboard name
     * @param start     start rank (0-indexed, inclusive)
     * @param end       end rank (inclusive)
     * @return ranked list within the specified range
     */
    @Override
    public List<RankedMemberResponse> getMembersByRankRange(String boardName, long start, long end) {
        String key = resolveKey(boardName);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                sortedSetRepository.reverseRangeWithScores(key, start, end);
        return toRankedList(tuples, key);
    }

    /**
     * Returns members whose scores fall within {@code [min, max]} using
     * {@code ZRANGEBYSCORE min max WITHSCORES}.
     *
     * @param boardName leaderboard name
     * @param min       minimum score (inclusive)
     * @param max       maximum score (inclusive)
     * @return list of members within the score range
     */
    @Override
    public List<RankedMemberResponse> getMembersByScoreRange(String boardName, double min, double max) {
        String key = resolveKey(boardName);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                sortedSetRepository.rangeByScoreWithScores(key, min, max);
        return toRankedList(tuples, key);
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Increment Member Score                                    |
     * +------------------------------------------------------------+
     * |  Atomically increments a member's score using              |
     * |  {@code ZINCRBY delta member}. Delta can be negative.      |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Redis command flow:
     * <pre>
     *  +---------+  ZINCRBY delta member  +------+
     *  |   App   |----------------------&gt;| ZSET |---&gt; new score
     *  +---------+                       +------+
     * </pre>
     *
     * @param boardName leaderboard name
     * @param memberId  member identifier
     * @param delta     amount to add (can be negative)
     * @return the new score after increment
     */
    @Override
    public Double incrementMemberScore(String boardName, String memberId, double delta) {
        String key = resolveKey(boardName);
        Double newScore = sortedSetRepository.incrementScore(key, memberId, delta);
        log.info("Incremented member={} by {} in board={}, newScore={}", memberId, delta, boardName, newScore);
        return newScore;
    }

    /**
     * <pre>
     * +------------------------------------------------------------+
     * |  Remove Member                                             |
     * +------------------------------------------------------------+
     * |  Removes a member from the leaderboard using               |
     * |  {@code ZREM member}.                                      |
     * +------------------------------------------------------------+
     * </pre>
     *
     * <p>Redis command flow:
     * <pre>
     *  +---------+  ZREM member  +------+
     *  |   App   |--------------&gt;| ZSET |
     *  +---------+              +------+
     * </pre>
     *
     * @param boardName leaderboard name
     * @param memberId  member identifier
     * @return {@code true} if the member was removed; {@code false} if not found
     */
    @Override
    public boolean removeMember(String boardName, String memberId) {
        String key = resolveKey(boardName);
        Long removed = sortedSetRepository.remove(key, memberId);
        log.info("Removed member={} from board={} (count={})", memberId, boardName, removed);
        return removed != null && removed > 0;
    }

    /**
     * Returns the total number of members on the leaderboard using {@code ZCARD}.
     *
     * @param boardName leaderboard name
     * @return member count
     */
    @Override
    public Long getBoardSize(String boardName) {
        return sortedSetRepository.size(resolveKey(boardName));
    }

    /**
     * Returns the count of members whose scores fall within {@code [min, max]}
     * using {@code ZCOUNT}.
     *
     * @param boardName leaderboard name
     * @param min       minimum score (inclusive)
     * @param max       maximum score (inclusive)
     * @return count of members in the score range
     */
    @Override
    public Long getCountInScoreRange(String boardName, double min, double max) {
        return sortedSetRepository.count(resolveKey(boardName), min, max);
    }

    private String resolveKey(String boardName) {
        return KEY_PREFIX + boardName;
    }

    private List<RankedMemberResponse> toRankedList(Set<ZSetOperations.TypedTuple<String>> tuples, String key) {
        if (tuples == null) {
            return List.of();
        }
        List<RankedMemberResponse> result = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String memberId = tuple.getValue();
            if (memberId == null) {
                continue;
            }
            Long rank = sortedSetRepository.reverseRank(key, memberId);
            if (rank == null) {
                log.warn("Member {} no longer exists in sorted set, skipping", memberId);
                continue;
            }
            result.add(RankedMemberResponse.builder()
                    .memberId(memberId)
                    .score(tuple.getScore())
                    .rank(rank)
                    .build());
        }
        return result;
    }
}
