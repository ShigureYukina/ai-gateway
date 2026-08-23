package io.gateway.oss.admin.observability;

import io.gateway.oss.core.contract.SystemConfigView;
import io.gateway.oss.core.config.BackendSelector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates {@link AggregateMetricStore} beans for the gateway-admin module.
 * <p>
 * TraceStore beans are provided by gateway-core's {@link io.gateway.oss.admin.observability.ObservabilityStoreConfig}.
 */
@Configuration
public class AdminObservabilityStoreConfig {

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "in_memory", matchIfMissing = true)
    static class AggMetricInMemoryConfig {
        @Bean
        public AggregateMetricStore inMemoryAggregateMetricStore() {
            return new InMemoryAggregateMetricStore();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "redis")
    static class AggMetricRedisConfig {
        @Bean
        public AggregateMetricStore redisAggregateMetricStore(StringRedisTemplate redisTemplate, SystemConfigView configView) {
            return new RedisAggregateMetricStore(redisTemplate, configView);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "postgresql")
    static class AggMetricPostgresConfig {
        @Bean
        public AggregateMetricStore postgresAggregateMetricStore(JdbcTemplate jdbc, SystemConfigView configView) {
            return new PostgresAggregateMetricStore(jdbc, configView.getSharedState().getKeyPrefix());
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "hybrid")
    static class AggMetricHybridConfig {

        private final StringRedisTemplate redisTemplate;
        private final JdbcTemplate jdbcTemplate;
        private final SystemConfigView configView;

        AggMetricHybridConfig(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate,
                                      SystemConfigView configView) {
            this.redisTemplate = redisTemplate;
            this.jdbcTemplate = jdbcTemplate;
            this.configView = configView;
        }

        @Bean
        public AggregateMetricStore hybridAggregateMetricStore() {
            return BackendSelector.resolve(
                configView.getStore().getAggregateMetrics(),
                InMemoryAggregateMetricStore::new,
                () -> new RedisAggregateMetricStore(redisTemplate, configView),
                () -> new PostgresAggregateMetricStore(jdbcTemplate, configView.getSharedState().getKeyPrefix())
            );
        }
    }
}
