package com.example.redis.repository;

import com.example.redis.model.CacheProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * <b>Cache Product Repository</b>
 *
 * <p>Spring Data JPA repository for {@link CacheProduct} CRUD operations. All queries are partitioned by
 * {@code patternName} so each caching pattern (cache-aside, write-through, etc.) operates on an isolated data slice.
 */
public interface CacheProductRepository extends JpaRepository<CacheProduct, Long> {

    /**
     * Finds a single product by its key within a specific caching pattern.
     *
     * @param productKey  the cache key
     * @param patternName the caching pattern partition
     * @return the matching {@link CacheProduct}, if present
     */
    Optional<CacheProduct> findByProductKeyAndPatternName(String productKey, String patternName);

    /**
     * Retrieves all products belonging to a given caching pattern.
     *
     * @param patternName the caching pattern partition
     * @return list of {@link CacheProduct} entries for that pattern
     */
    List<CacheProduct> findAllByPatternName(String patternName);

    /**
     * Deletes all products belonging to a given caching pattern.
     *
     * @param patternName the caching pattern partition to clear
     */
    void deleteByPatternName(String patternName);
}
