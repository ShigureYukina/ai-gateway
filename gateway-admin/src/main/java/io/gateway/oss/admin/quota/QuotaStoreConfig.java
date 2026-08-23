package io.gateway.oss.admin.quota;

import io.gateway.oss.core.contract.SystemConfigView;
import io.gateway.oss.core.config.BackendSelector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Consolidated quota-domain bean configuration.
 * Replaces quota bean definitions previously spread across
 * StateBackendConfig, RedisBackendConfig, PostgresBackendConfig, and HybridStoreConfig.
 */
@Configuration
public class QuotaStoreConfig {

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "in_memory", matchIfMissing = true)
    static class QuotaInMemoryConfig {

        @Bean
        public ClientUsageStore inMemoryClientUsageStore() {
            return new InMemoryClientUsageStore();
        }

        @Bean
        public ClientCostStore inMemoryClientCostStore() {
            return new InMemoryClientCostStore();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "redis")
    static class QuotaRedisConfig {

        @Bean
        public ClientUsageStore redisClientUsageStore(StringRedisTemplate redisTemplate, SystemConfigView configView) {
            return new RedisClientUsageStore(redisTemplate, configView);
        }

        @Bean
        public ClientCostStore redisClientCostStore(StringRedisTemplate redisTemplate, SystemConfigView configView) {
            return new RedisClientCostStore(redisTemplate, configView);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "postgresql")
    static class QuotaPostgresConfig {

        @Bean
        public ClientUsageStore postgresClientUsageStore(JdbcTemplate jdbc, SystemConfigView configView) {
            String namespace = configView.getSharedState().getKeyPrefix();
            var pgStore = new PostgresClientUsageStore(jdbc, namespace);
            return new BufferedClientUsageStore(jdbc, pgStore, namespace);
        }

        @Bean
        public ClientCostStore postgresClientCostStore(JdbcTemplate jdbc, SystemConfigView configView) {
            String namespace = configView.getSharedState().getKeyPrefix();
            var pgStore = new PostgresClientCostStore(jdbc, namespace);
            return new BufferedClientCostStore(jdbc, pgStore, namespace);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "hybrid")
    static class QuotaHybridConfig {

        private final StringRedisTemplate redisTemplate;
        private final JdbcTemplate jdbcTemplate;
        private final SystemConfigView configView;

        QuotaHybridConfig(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate,
                                  SystemConfigView configView) {
            this.redisTemplate = redisTemplate;
            this.jdbcTemplate = jdbcTemplate;
            this.configView = configView;
        }

        @Bean
        public ClientUsageStore hybridClientUsageStore() {
            String namespace = configView.getSharedState().getKeyPrefix();
            return BackendSelector.resolve(
                configView.getStore().getUsage(),
                InMemoryClientUsageStore::new,
                () -> new RedisClientUsageStore(redisTemplate, configView),
                () -> new BufferedClientUsageStore(jdbcTemplate,
                    new PostgresClientUsageStore(jdbcTemplate, namespace), namespace)
            );
        }

        @Bean
        public ClientCostStore hybridClientCostStore() {
            String namespace = configView.getSharedState().getKeyPrefix();
            return BackendSelector.resolve(
                configView.getStore().getCost(),
                InMemoryClientCostStore::new,
                () -> new RedisClientCostStore(redisTemplate, configView),
                () -> new BufferedClientCostStore(jdbcTemplate,
                    new PostgresClientCostStore(jdbcTemplate, namespace), namespace)
            );
        }
    }
}
