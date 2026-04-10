package com.example.redis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <b>Ranked Member Response</b>
 *
 * <p>Response DTO for a single leaderboard member, containing the member identifier, their current score, and their
 * rank within the board (0-based, ascending by score). Returned by detail lookups, top-N queries, and range queries
 * on the leaderboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankedMemberResponse {

    private String memberId;
    private Double score;
    private Long rank;
}
