package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.BackendSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consolidates all upstream state bean creation (RouteStateStore, ProviderRuntimeStateStore)
 * from the 4 backend config files into a single class with inner @Configuration classes
 * per backend variant.
 */
@Configuration
public class UpstreamStateStoreConfig {

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "in_memory", matchIfMissing = true)
    static class UpstreamStateInMemoryConfig {

        @Bean
        public RouteStateStore inMemoryRouteStateStore() {
            return new InMemoryRouteStateStore();
        }

        @Bean
        public ProviderRuntimeStateStore inMemoryProviderRuntimeStateStore() {
            return new InMemoryProviderRuntimeStateStore();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "redis")
    static class UpstreamStateRedisConfig {

        @Bean
        public RouteStateStore redisRouteStateStore(StringRedisTemplate redisTemplate, GatewayProperties properties) {
            return new RedisRouteStateStore(redisTemplate, properties);
        }

        @Bean
        public ProviderRuntimeStateStore redisProviderRuntimeStateStore(StringRedisTemplate redisTemplate,
                                                                        GatewayProperties properties,
                                                                        ObjectMapper objectMapper) {
            return new RedisProviderRuntimeStateStore(redisTemplate, properties, objectMapper);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "postgresql")
    static class UpstreamStatePostgresConfig {

        @Bean
        public RouteStateStore postgresRouteStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper, GatewayProperties properties) {
            return new PostgresRouteStateStore(jdbc, objectMapper, properties.getSharedState().getKeyPrefix());
        }

        @Bean
        public ProviderRuntimeStateStore postgresProviderRuntimeStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper, GatewayProperties properties) {
            return new PostgresProviderRuntimeStateStore(jdbc, objectMapper, properties.getSharedState().getKeyPrefix());
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "hybrid")
    static class UpstreamStateHybridConfig {

        private final StringRedisTemplate redisTemplate;
        private final JdbcTemplate jdbcTemplate;
        private final GatewayProperties properties;
        private final ObjectMapper objectMapper;

        UpstreamStateHybridConfig(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate,
                                          GatewayProperties properties, ObjectMapper objectMapper) {
            this.redisTemplate = redisTemplate;
            this.jdbcTemplate = jdbcTemplate;
            this.properties = properties;
            this.objectMapper = objectMapper;
        }

        @Bean
        public RouteStateStore hybridRouteStateStore() {
            return BackendSelector.resolve(
                properties.getStore().getRouteState(),
                InMemoryRouteStateStore::new,
                () -> new RedisRouteStateStore(redisTemplate, properties),
                () -> new PostgresRouteStateStore(jdbcTemplate, objectMapper, properties.getSharedState().getKeyPrefix())
            );
        }

        @Bean
        public ProviderRuntimeStateStore hybridProviderRuntimeStateStore() {
            return BackendSelector.resolve(
                properties.getStore().getProviderState(),
                InMemoryProviderRuntimeStateStore::new,
                () -> new RedisProviderRuntimeStateStore(redisTemplate, properties, objectMapper),
                () -> new PostgresProviderRuntimeStateStore(jdbcTemplate, objectMapper, properties.getSharedState().getKeyPrefix())
            );
        }
    }
}
