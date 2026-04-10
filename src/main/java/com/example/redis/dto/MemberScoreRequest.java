package com.example.redis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <b>Member Score Request</b>
 *
 * <p>Request body for leaderboard member operations such as adding a new member or updating an existing member's
 * score. Both {@code memberId} and {@code score} are mandatory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberScoreRequest {

    @NotBlank(message = "Member ID is required")
    private String memberId;

    @NotNull(message = "Score is required")
    private Double score;
}
