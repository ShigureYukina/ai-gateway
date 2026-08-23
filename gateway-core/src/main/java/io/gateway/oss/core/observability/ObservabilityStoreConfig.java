package io.gateway.oss.core.observability;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.BackendSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consolidates all observability-domain bean creation (TraceStore only).
 * AggregateMetricStore beans are provided by gateway-admin module.
 */
@Configuration
@Import({
        ObservabilityStoreConfig.ObservabilityInMemoryConfig.class,
        ObservabilityStoreConfig.ObservabilityRedisConfig.class,
        ObservabilityStoreConfig.ObservabilityPostgresConfig.class,
        ObservabilityStoreConfig.ObservabilityHybridConfig.class
})
public class ObservabilityStoreConfig {

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "in_memory", matchIfMissing = true)
    static class ObservabilityInMemoryConfig {

        @Bean
        public TraceStore inMemoryTraceStore() {
            return new InMemoryTraceStore();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "redis")
    static class ObservabilityRedisConfig {

        @Bean
        public TraceStore redisTraceStore(StringRedisTemplate redisTemplate,
                                           GatewayProperties properties,
                                           ObjectMapper objectMapper) {
            return new RedisTraceStore(redisTemplate, properties, objectMapper);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "postgresql")
    static class ObservabilityPostgresConfig {

        @Bean
        public TraceStore postgresTraceStore(JdbcTemplate jdbc, GatewayProperties properties) {
            return new PostgresTraceStore(jdbc, properties);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "hybrid")
    static class ObservabilityHybridConfig {

        private final StringRedisTemplate redisTemplate;
        private final JdbcTemplate jdbcTemplate;
        private final GatewayProperties properties;

        ObservabilityHybridConfig(StringRedisTemplate redisTemplate,
                                         JdbcTemplate jdbcTemplate,
                                         GatewayProperties properties) {
            this.redisTemplate = redisTemplate;
            this.jdbcTemplate = jdbcTemplate;
            this.properties = properties;
        }

        @Bean
        public TraceStore hybridTraceStore(ObjectMapper objectMapper) {
            return BackendSelector.resolve(
                properties.getStore().getTrace(),
                InMemoryTraceStore::new,
                () -> new RedisTraceStore(redisTemplate, properties, objectMapper),
                () -> new PostgresTraceStore(jdbcTemplate, properties)
            );
        }
    }
}
