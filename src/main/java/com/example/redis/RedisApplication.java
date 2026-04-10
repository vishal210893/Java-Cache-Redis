package com.example.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * <b>Redis Learning Application</b>
 *
 * <p>Spring Boot entry point for the Redis Sorted Set and Caching Patterns learning application. Bootstraps the
 * application context and prints a startup banner with all available endpoints via the {@link StartupLogger} inner
 * component.
 */
@SpringBootApplication
public class RedisApplication {

    /**
     * Application entry point.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(RedisApplication.class, args);
    }

    /**
     * <b>Startup Logger</b>
     *
     * <p>{@link ApplicationRunner} that prints a formatted banner after the application context is fully
     * initialized, listing the active port, Swagger UI URL, Actuator health endpoint, and all available REST API
     * paths.
     */
    @Slf4j
    @Component
    static class StartupLogger implements ApplicationRunner {

        private final Environment env;

        StartupLogger(Environment env) {
            this.env = env;
        }

        @Override
        public void run(ApplicationArguments args) {
            String port = env.getProperty("server.port", "8080");
            log.info("""
                    ==========================================================
                      Redis Sorted Set Learning App is READY
                      Port:        {}
                      Swagger UI:  http://localhost:{}/swagger-ui.html
                      Actuator:    http://localhost:{}/actuator/health
                      --------------------------------------------------
                      Endpoints:
                        Leaderboard (Sorted Set): /api/leaderboards/{{boardName}}
                        LRU Cache:                /api/cache/lru
                        LFU Cache:                /api/cache/lfu
                      --------------------------------------------------
                      Caching Patterns:
                        1. Cache-Aside:    /api/cache/patterns/cache-aside
                        2. Read-Through:   /api/cache/patterns/read-through
                        3. Write-Through:  /api/cache/patterns/write-through
                        4. Write-Back:     /api/cache/patterns/write-back
                        5. Write-Around:   /api/cache/patterns/write-around
                        6. Refresh-Ahead:  /api/cache/patterns/refresh-ahead
                    ==========================================================""",
                    port, port, port);
        }
    }
}
