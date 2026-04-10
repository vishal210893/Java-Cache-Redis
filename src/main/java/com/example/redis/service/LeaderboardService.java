package com.example.redis.service;

import com.example.redis.dto.MemberScoreRequest;
import com.example.redis.dto.RankedMemberResponse;

import java.util.List;

/**
 * <b>Leaderboard Service Interface</b>
 *
 * <p>Contract for leaderboard operations backed by Redis sorted sets (ZSETs). Supports multiple named boards,
 * member CRUD, score manipulation, and flexible ranking queries (top-N, rank-range, score-range).
 *
 * <pre>
 *  ┌────────────┐    ┌────────────────────┐    ┌───────────┐
 *  │ Controller │───>│ LeaderboardService  │───>│ Redis ZSET│
 *  └────────────┘    └────────────────────┘    └───────────┘
 * </pre>
 */
public interface LeaderboardService {

    /**
     * Adds or updates a member's score on the named leaderboard.
     *
     * @param boardName the leaderboard name (becomes the Redis key)
     * @param request   the member ID and score to set
     * @return {@code true} if the member was newly added, {@code false} if updated
     */
    boolean addMember(String boardName, MemberScoreRequest request);

    /**
     * Retrieves a single member's details including score and rank.
     *
     * @param boardName the leaderboard name
     * @param memberId  the member to look up
     * @return a {@link RankedMemberResponse} with score and rank
     * @throws com.example.redis.exception.MemberNotFoundException if not found
     */
    RankedMemberResponse getMemberDetails(String boardName, String memberId);

    /**
     * Returns the top N members ordered by score descending.
     *
     * @param boardName the leaderboard name
     * @param count     how many top members to return
     * @return list of {@link RankedMemberResponse} in descending score order
     */
    List<RankedMemberResponse> getTopMembers(String boardName, int count);

    /**
     * Returns members within a rank range (0-based, ascending by score).
     *
     * @param boardName the leaderboard name
     * @param start     start rank (inclusive)
     * @param end       end rank (inclusive)
     * @return list of {@link RankedMemberResponse} in the rank window
     */
    List<RankedMemberResponse> getMembersByRankRange(String boardName, long start, long end);

    /**
     * Returns members whose scores fall within the given range.
     *
     * @param boardName the leaderboard name
     * @param min       minimum score (inclusive)
     * @param max       maximum score (inclusive)
     * @return list of {@link RankedMemberResponse} within the score window
     */
    List<RankedMemberResponse> getMembersByScoreRange(String boardName, double min, double max);

    /**
     * Increments a member's score by the given delta (can be negative).
     *
     * @param boardName the leaderboard name
     * @param memberId  the member whose score to adjust
     * @param delta     the amount to add
     * @return the new score after incrementing
     */
    Double incrementMemberScore(String boardName, String memberId, double delta);

    /**
     * Removes a member from the leaderboard.
     *
     * @param boardName the leaderboard name
     * @param memberId  the member to remove
     * @return {@code true} if the member existed and was removed
     */
    boolean removeMember(String boardName, String memberId);

    /**
     * Returns the total number of members on the leaderboard.
     *
     * @param boardName the leaderboard name
     * @return the board size (cardinality)
     */
    Long getBoardSize(String boardName);

    /**
     * Counts members whose scores fall within the given range.
     *
     * @param boardName the leaderboard name
     * @param min       minimum score (inclusive)
     * @param max       maximum score (inclusive)
     * @return the count of members in the score range
     */
    Long getCountInScoreRange(String boardName, double min, double max);
}
