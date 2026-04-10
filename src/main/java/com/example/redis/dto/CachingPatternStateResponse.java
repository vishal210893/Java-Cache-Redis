package com.example.redis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * <b>Caching Pattern State Response</b>
 *
 * <p>Rich state snapshot for caching pattern endpoints, exposing data across all three layers: L1 (Caffeine
 * in-process), L2 (Redis), and the backing database (PostgreSQL). Also carries pattern-specific metadata and an
 * ASCII diagram that visually describes the data flow for the active caching strategy.
 *
 * <pre>
 *  ┌──────────┐    ┌──────────────┐    ┌───────────┐    ┌────────────┐
 *  │ Response │<───│ cacheEntries │    │ dbEntries │    │  metadata  │
 *  │  (JSON)  │    │   (L1+L2)    │    │   (DB)    │    │ hit/miss   │
 *  └──────────┘    └──────────────┘    └───────────┘    └────────────┘
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CachingPatternStateResponse {

    private String pattern;
    private String description;
    private long cacheSize;
    private long dbSize;
    private List<CacheEntry> cacheEntries;
    private Map<String, String> dbEntries;
    private Map<String, Object> metadata;
    private String asciiDiagram;
}
