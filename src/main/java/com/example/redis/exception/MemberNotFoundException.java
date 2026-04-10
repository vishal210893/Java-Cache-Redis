package com.example.redis.exception;

/**
 * <b>Member Not Found Exception</b>
 *
 * <p>Thrown when a leaderboard operation references a member that does not exist in the specified board. Handled
 * globally by {@link GlobalExceptionHandler} which maps it to HTTP 404.
 */
public class MemberNotFoundException extends RuntimeException {

    /**
     * Constructs the exception with a formatted message including the member and board.
     *
     * @param memberId  the member identifier that was not found
     * @param boardName the leaderboard that was searched
     */
    public MemberNotFoundException(String memberId, String boardName) {
        super("Member '%s' not found in board '%s'".formatted(memberId, boardName));
    }
}
