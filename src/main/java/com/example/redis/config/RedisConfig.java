package com.example.redis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * <b>Redis Template Configuration</b>
 *
 * <p>Configures a {@link RedisTemplate} with {@link StringRedisSerializer} for keys, values, hash-keys, and
 * hash-values so every Redis operation uses human-readable UTF-8 strings instead of the default JDK serialization.
 *
 * <pre>
 *  ┌──────────┐    ┌───────────────┐    ┌───────────┐
 *  │ Service  │───>│ RedisTemplate │───>│   Redis   │
 *  └──────────┘    └───────────────┘    └───────────┘
 *         all keys/values serialized as plain strings
 * </pre>
 */
@Configuration
public class RedisConfig {

    /**
     * <b>RedisTemplate Bean</b>
     *
     * <p>Provides a fully-configured {@link RedisTemplate} wired with {@link StringRedisSerializer} on all four
     * channels (key, value, hash-key, hash-value). This avoids opaque binary blobs in Redis and makes CLI debugging
     * trivial.
     *
     * @param connectionFactory the Spring-managed {@link RedisConnectionFactory}
     * @return a ready-to-use {@code RedisTemplate<String, String>}
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
