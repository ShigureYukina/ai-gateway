package io.gateway.oss.core.limit;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.BackendSelector;
import io.gateway.oss.core.security.LoginRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consolidated limit-domain bean wiring for all backend variants.
 * <p>
 * Creates {@link ClientRateLimiter} and {@link LoginRateLimiter}
 * beans based on the {@code gateway.shared-state.backend} property.
 * ClientTpmStore beans are provided by gateway-admin module.
 */
@Configuration
public class LimitStoreConfig {

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "in_memory", matchIfMissing = true)
    static class LimitInMemoryConfig {

        @Bean
        public ClientRateLimiter inMemoryClientRateLimiter(GatewayProperties properties) {
            return new InMemoryRateLimiter(properties);
        }

        @Bean
        public LoginRateLimiter loginRateLimiter() {
            return new LoginRateLimiter();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "redis")
    static class LimitRedisConfig {

        @Bean
        public ClientRateLimiter redisClientRateLimiter(StringRedisTemplate redisTemplate, GatewayProperties properties) {
            return new RedisRateLimiter(redisTemplate, properties);
        }

        @Bean
        public LoginRateLimiter loginRateLimiter(StringRedisTemplate redisTemplate, GatewayProperties properties) {
            return new LoginRateLimiter(properties, redisTemplate);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "postgresql")
    static class LimitPostgresConfig {

        @Bean
        public ClientRateLimiter postgresClientRateLimiter(JdbcTemplate jdbc, GatewayProperties properties) {
            return new PostgresClientRateLimiter(jdbc, properties);
        }

        @Bean
        public LoginRateLimiter loginRateLimiter() {
            return new LoginRateLimiter();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "hybrid")
    static class LimitHybridConfig {

        private final StringRedisTemplate redisTemplate;
        private final JdbcTemplate jdbcTemplate;
        private final GatewayProperties properties;

        LimitHybridConfig(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate,
                                 GatewayProperties properties) {
            this.redisTemplate = redisTemplate;
            this.jdbcTemplate = jdbcTemplate;
            this.properties = properties;
        }

        @Bean
        public ClientRateLimiter hybridClientRateLimiter() {
            return BackendSelector.resolve(
                properties.getStore().getRateLimiter(),
                () -> new InMemoryRateLimiter(properties),
                () -> new RedisRateLimiter(redisTemplate, properties),
                () -> new PostgresClientRateLimiter(jdbcTemplate, properties)
            );
        }

        @Bean
        public LoginRateLimiter loginRateLimiter() {
            return new LoginRateLimiter(properties, redisTemplate);
        }
    }
}
