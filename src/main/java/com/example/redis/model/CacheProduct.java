package com.example.redis.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <b>CacheProduct JPA Entity</b>
 *
 * <p>Persistent representation of a cache entry in the {@code cache_products} table. Each row stores a key-value
 * pair scoped to a specific caching pattern via {@code patternName}, enforced by a composite unique constraint on
 * ({@code product_key}, {@code pattern_name}).
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
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cache_products",
        uniqueConstraints = @UniqueConstraint(columnNames = {"product_key", "pattern_name"}))
public class CacheProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_key", nullable = false)
    private String productKey;

    @Column(name = "product_value", nullable = false, length = 1000)
    private String productValue;

    @Column(name = "pattern_name", nullable = false)
    private String patternName;
}
