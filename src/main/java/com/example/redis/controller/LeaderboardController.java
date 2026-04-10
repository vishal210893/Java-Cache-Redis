package com.example.redis.controller;

import com.example.redis.dto.ApiResponse;
import com.example.redis.dto.MemberScoreRequest;
import com.example.redis.dto.RankedMemberResponse;
import com.example.redis.service.LeaderboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <pre>
 * +------------------------------------------------------------+
 * |  Leaderboard Controller                                    |
 * +------------------------------------------------------------+
 * |  REST controller exposing Redis Sorted Set operations      |
 * |  through a leaderboard API. Each board is a ZSET keyed     |
 * |  by {@code leaderboard:{boardName}}.                       |
 * +------------------------------------------------------------+
 * </pre>
 *
 * <p>Endpoints (base path {@code /api/leaderboards/{boardName}}):
 * <pre>
 *  +-------------------------------+--------+--------+---------------------------+
 *  | Endpoint                      | Method | Redis  | Description               |
 *  +-------------------------------+--------+--------+---------------------------+
 *  | /members                      | POST   | ZADD   | Add/update member score   |
 *  | /members/{memberId}           | GET    | ZSCORE | Get member score + rank   |
 *  |                               |        |+ZREVRANK                          |
 *  | /top?count=N                  | GET    |ZREVRANGE| Top N by score (desc)    |
 *  | /rank-range?start=S&amp;end=E     | GET    |ZREVRANGE| Members by rank range   |
 *  | /score-range?min=M&amp;max=X      | GET    |ZRANGEBYSCORE| Members by score    |
 *  | /members/{memberId}/score     | PATCH  | ZINCRBY| Increment member score    |
 *  | /members/{memberId}           | DELETE | ZREM   | Remove member             |
 *  | /size                         | GET    | ZCARD  | Total member count        |
 *  | /count?min=M&amp;max=X            | GET    | ZCOUNT | Count in score range      |
 *  +-------------------------------+--------+--------+---------------------------+
 * </pre>
 *
 * @see LeaderboardService
 * @see com.example.redis.service.impl.LeaderboardServiceImpl
 */
@RestController
@RequestMapping("/api/leaderboards/{boardName}")
@RequiredArgsConstructor
@Tag(name = "Leaderboard", description = "Redis Sorted Set operations via a Leaderboard API")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    /**
     * Adds or updates a member's score on the leaderboard using {@code ZADD}.
     *
     * @param boardName leaderboard name (path variable)
     * @param request   member id and score
     * @return {@code true} if newly added, {@code false} if score was updated
     */
    @Operation(summary = "ZADD - Add member with score")
    @PostMapping("/members")
    public ResponseEntity<ApiResponse<Boolean>> addMember(
            @PathVariable String boardName,
            @Valid @RequestBody MemberScoreRequest request) {
        boolean isNew = leaderboardService.addMember(boardName, request);
        String msg = isNew ? "Member added" : "Member score updated";
        return ResponseEntity.status(isNew ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(msg, isNew));
    }

    /**
     * Retrieves a member's score ({@code ZSCORE}) and descending rank
     * ({@code ZREVRANK}).
     *
     * @param boardName leaderboard name
     * @param memberId  member identifier
     * @return score and rank details
     */
    @Operation(summary = "ZSCORE + ZREVRANK - Get member details")
    @GetMapping("/members/{memberId}")
    public ResponseEntity<ApiResponse<RankedMemberResponse>> getMember(
            @PathVariable String boardName,
            @PathVariable String memberId) {
        return ResponseEntity.ok(
                ApiResponse.ok(leaderboardService.getMemberDetails(boardName, memberId)));
    }

    /**
     * Returns the top N members sorted by score descending using
     * {@code ZREVRANGE 0 (count-1) WITHSCORES}.
     *
     * @param boardName leaderboard name
     * @param count     number of top members (default 10)
     * @return ranked list, highest score first
     */
    @Operation(summary = "ZREVRANGE - Get top N members (highest scores first)")
    @GetMapping("/top")
    public ResponseEntity<ApiResponse<List<RankedMemberResponse>>> getTop(
            @PathVariable String boardName,
            @RequestParam(defaultValue = "10") int count) {
        return ResponseEntity.ok(
                ApiResponse.ok(leaderboardService.getTopMembers(boardName, count)));
    }

    /**
     * Returns members within the specified descending rank range using
     * {@code ZREVRANGE start end WITHSCORES} (0-indexed).
     *
     * @param boardName leaderboard name
     * @param start     start rank (default 0)
     * @param end       end rank (default 9)
     * @return ranked list within the specified range
     */
    @Operation(summary = "ZREVRANGE - Get members by rank range (0-indexed, highest first)")
    @GetMapping("/rank-range")
    public ResponseEntity<ApiResponse<List<RankedMemberResponse>>> getByRankRange(
            @PathVariable String boardName,
            @RequestParam(defaultValue = "0") long start,
            @RequestParam(defaultValue = "9") long end) {
        return ResponseEntity.ok(
                ApiResponse.ok(leaderboardService.getMembersByRankRange(boardName, start, end)));
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
    @Operation(summary = "ZRANGEBYSCORE - Get members within a score range")
    @GetMapping("/score-range")
    public ResponseEntity<ApiResponse<List<RankedMemberResponse>>> getByScoreRange(
            @PathVariable String boardName,
            @RequestParam double min,
            @RequestParam double max) {
        return ResponseEntity.ok(
                ApiResponse.ok(leaderboardService.getMembersByScoreRange(boardName, min, max)));
    }

    /**
     * Atomically increments a member's score using {@code ZINCRBY delta member}.
     * Delta can be negative to decrement.
     *
     * @param boardName leaderboard name
     * @param memberId  member identifier
     * @param delta     amount to add (can be negative)
     * @return the new score after increment
     */
    @Operation(summary = "ZINCRBY - Increment a member's score")
    @PatchMapping("/members/{memberId}/score")
    public ResponseEntity<ApiResponse<Double>> incrementScore(
            @PathVariable String boardName,
            @PathVariable String memberId,
            @RequestParam double delta) {
        Double newScore = leaderboardService.incrementMemberScore(boardName, memberId, delta);
        return ResponseEntity.ok(ApiResponse.ok("Score incremented", newScore));
    }

    /**
     * Removes a member from the leaderboard using {@code ZREM member}.
     *
     * @param boardName leaderboard name
     * @param memberId  member identifier
     * @return {@code true} if removed, 404 if not found
     */
    @Operation(summary = "ZREM - Remove a member")
    @DeleteMapping("/members/{memberId}")
    public ResponseEntity<ApiResponse<Boolean>> removeMember(
            @PathVariable String boardName,
            @PathVariable String memberId) {
        boolean removed = leaderboardService.removeMember(boardName, memberId);
        return removed
                ? ResponseEntity.ok(ApiResponse.ok("Member removed", true))
                : ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Member not found"));
    }

    /**
     * Returns the total member count on the leaderboard using {@code ZCARD}.
     *
     * @param boardName leaderboard name
     * @return total number of members
     */
    @Operation(summary = "ZCARD - Get total member count")
    @GetMapping("/size")
    public ResponseEntity<ApiResponse<Long>> getSize(@PathVariable String boardName) {
        return ResponseEntity.ok(ApiResponse.ok(leaderboardService.getBoardSize(boardName)));
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
    @Operation(summary = "ZCOUNT - Count members within a score range")
    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> getCountInRange(
            @PathVariable String boardName,
            @RequestParam double min,
            @RequestParam double max) {
        return ResponseEntity.ok(
                ApiResponse.ok(leaderboardService.getCountInScoreRange(boardName, min, max)));
    }
}
