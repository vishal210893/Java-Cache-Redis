package com.example.redis.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * <b>Seed Database Request</b>
 *
 * <p>Request body to seed the PostgreSQL database with test data for a given caching pattern. Contains a non-empty
 * map of key-value pairs that will be persisted as {@link com.example.redis.model.CacheProduct} rows.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeedDbRequest {

    @NotEmpty(message = "Entries map must not be empty")
    private Map<String, String> entries;
}
