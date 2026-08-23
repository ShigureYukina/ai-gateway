package io.gateway.oss.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.scheduler.Scheduler;

/**
 * Auto-configuration for ConfigStore beans.
 * Consolidates ConfigStore bean wiring from StateBackendConfig, RedisBackendConfig,
 * PostgresBackendConfig, and HybridStoreConfig into a single class.
 */
@Configuration
@Import({
        ConfigStoreAutoConfig.ConfigStoreInMemoryConfig.class,
        ConfigStoreAutoConfig.ConfigStoreRedisConfig.class,
        ConfigStoreAutoConfig.ConfigStorePostgresConfig.class,
        ConfigStoreAutoConfig.ConfigStoreHybridConfig.class
})
public class ConfigStoreAutoConfig {

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "in_memory", matchIfMissing = true)
    static class ConfigStoreInMemoryConfig {

        @Bean
        public InMemoryConfigStore inMemoryConfigStore() {
            return new InMemoryConfigStore();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "redis")
    static class ConfigStoreRedisConfig {

        @Bean
        public ConfigStore redisConfigStore(StringRedisTemplate redisTemplate,
                                            GatewayProperties properties,
                                            @Qualifier("boundedElasticScheduler") Scheduler boundedElasticScheduler) {
            return new RedisConfigStore(redisTemplate, properties, boundedElasticScheduler);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "postgresql")
    static class ConfigStorePostgresConfig {

        @Bean
        public ConfigStore postgresConfigStore(JdbcTemplate jdbc,
                                               GatewayProperties properties,
                                               @Qualifier("boundedElasticScheduler") Scheduler boundedElasticScheduler) {
            return new PostgresConfigStore(jdbc, properties, boundedElasticScheduler);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "hybrid")
    static class ConfigStoreHybridConfig {

        private final StringRedisTemplate redisTemplate;
        private final JdbcTemplate jdbcTemplate;
        private final GatewayProperties properties;
        private final Scheduler boundedElasticScheduler;

        ConfigStoreHybridConfig(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate,
                                GatewayProperties properties,
                                @Qualifier("boundedElasticScheduler") Scheduler boundedElasticScheduler) {
            this.redisTemplate = redisTemplate;
            this.jdbcTemplate = jdbcTemplate;
            this.properties = properties;
            this.boundedElasticScheduler = boundedElasticScheduler;
        }

        @Bean
        public ConfigStore hybridConfigStore() {
            return BackendSelector.resolve(
                properties.getStore().getConfig(),
                InMemoryConfigStore::new,
                () -> new RedisConfigStore(redisTemplate, properties, boundedElasticScheduler),
                () -> new PostgresConfigStore(jdbcTemplate, properties, boundedElasticScheduler)
            );
        }
    }
}
