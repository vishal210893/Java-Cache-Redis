package com.example.redis.service.impl;

import com.example.redis.model.CacheProduct;
import com.example.redis.repository.CacheProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * <b>Database Service</b>
 *
 * <p>Database access layer backed by PostgreSQL (Aiven Cloud). Each caching pattern operates on its own partition
 * via {@code patternName}, so patterns never interfere with each other's data.
 *
 * <pre>
 *  ┌───────────┐    ┌──────────────────┐    ┌────────────┐
 *  │  Service  │───>│ DatabaseService   │───>│ PostgreSQL │
 *  └───────────┘    └──────────────────┘    └────────────┘
 * </pre>
 *
 * <pre>
 *  Table: cache_products
 *  ┌────┬─────────────┬───────────────┬───────────────┐
 *  │ id │ product_key │ product_value │ pattern_name  │
 *  ├────┼─────────────┼───────────────┼───────────────┤
 *  │  1 │ laptop      │ MacBook Pro   │ cache-aside   │
 *  │  2 │ laptop      │ MacBook Pro   │ write-through │
 *  └────┴─────────────┴───────────────┴───────────────┘
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseService {

    private final CacheProductRepository repository;

    /**
     * Reads a single value from the database for the given pattern and key.
     *
     * @param patternName the caching pattern partition
     * @param key         the product key to look up
     * @return the product value, or {@code null} if not found
     */
    public String read(String patternName, String key) {
        Optional<CacheProduct> product = repository
                .findByProductKeyAndPatternName(key, patternName);
        String value = product.map(CacheProduct::getProductValue).orElse(null);
        log.debug("DB READ pattern={} key={} value={}", patternName, key, value);
        return value;
    }

    /**
     * Writes (upserts) a key-value pair into the database for the given pattern.
     * Updates the existing row if the key already exists, otherwise inserts a new one.
     *
     * @param patternName the caching pattern partition
     * @param key         the product key
     * @param value       the product value to persist
     */
    @Transactional
    public void write(String patternName, String key, String value) {
        Optional<CacheProduct> existing = repository
                .findByProductKeyAndPatternName(key, patternName);
        if (existing.isPresent()) {
            CacheProduct product = existing.get();
            product.setProductValue(value);
            repository.save(product);
        } else {
            repository.save(CacheProduct.builder()
                    .productKey(key)
                    .productValue(value)
                    .patternName(patternName)
                    .build());
        }
        log.debug("DB WRITE pattern={} key={} value={}", patternName, key, value);
    }

    /**
     * Reads all key-value pairs from the database for the given pattern.
     *
     * @param patternName the caching pattern partition
     * @return an ordered map of all entries (insertion order preserved)
     */
    public Map<Object, Object> readAll(String patternName) {
        Map<Object, Object> result = new LinkedHashMap<>();
        repository.findAllByPatternName(patternName)
                .forEach(p -> result.put(p.getProductKey(), p.getProductValue()));
        return result;
    }

    /**
     * Deletes all database entries for the given pattern.
     *
     * @param patternName the caching pattern partition to clear
     */
    @Transactional
    public void clear(String patternName) {
        repository.deleteByPatternName(patternName);
        log.debug("DB CLEAR pattern={}", patternName);
    }

    /**
     * Seeds the database with multiple key-value pairs for testing or demo purposes.
     * Delegates to {@link #write(String, String, String)} for each entry (upsert semantics).
     *
     * @param patternName the caching pattern partition
     * @param entries     map of key-value pairs to persist
     */
    @Transactional
    public void seed(String patternName, Map<String, String> entries) {
        entries.forEach((key, value) -> write(patternName, key, value));
        log.info("DB SEED pattern={} with {} entries", patternName, entries.size());
    }
}
