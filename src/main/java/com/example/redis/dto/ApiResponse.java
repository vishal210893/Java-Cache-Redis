package com.example.redis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <b>Generic API Response Wrapper</b>
 *
 * <p>Uniform envelope returned by every REST endpoint in the application. Wraps the payload with success/failure
 * status, an optional human-readable message, and a timestamp. Null fields are omitted from the JSON output via
 * {@link JsonInclude.Include#NON_NULL}.
 *
 * <pre>
 *  {
 *    "success":   true,
 *    "message":   "optional detail",
 *    "data":      { ... },
 *    "timestamp": "2025-01-15T10:30:00"
 *  }
 * </pre>
 *
 * @param <T> the type of the payload carried in {@code data}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
     * <b>Factory: Success with Data</b>
     *
     * <p>Creates a successful response carrying only a payload.
     *
     * @param data the response payload
     * @param <T>  payload type
     * @return a success {@link ApiResponse} with the given data
     */
    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    /**
     * <b>Factory: Success with Message and Data</b>
     *
     * <p>Creates a successful response with an explicit message and a payload.
     *
     * @param message human-readable success description
     * @param data    the response payload
     * @param <T>     payload type
     * @return a success {@link ApiResponse} with message and data
     */
    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    /**
     * <b>Factory: Error Response</b>
     *
     * <p>Creates a failure response with no data, carrying only an error message for the client.
     *
     * @param message human-readable error description
     * @param <T>     payload type (always {@code null} in the response)
     * @return a failure {@link ApiResponse} with the given message
     */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }
}
