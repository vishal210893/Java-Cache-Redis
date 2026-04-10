package com.example.redis.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <b>Cache Put Request</b>
 *
 * <p>Request body for cache write operations. Both {@code key} and {@code value} are mandatory and validated via
 * Bean Validation ({@link NotBlank}). Used by LRU, LFU, and all caching pattern endpoints to accept new entries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachePutRequest {

    @NotBlank(message = "Key is required")
    private String key;

    @NotBlank(message = "Value is required")
    private String value;
}
