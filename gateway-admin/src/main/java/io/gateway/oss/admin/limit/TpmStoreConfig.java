package io.gateway.oss.admin.limit;

import io.gateway.oss.core.contract.SystemConfigView;
import io.gateway.oss.core.config.BackendSelector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates {@link ClientTpmStore} beans based on the shared-state backend.
 * TPM stores are in the gateway-admin module and are independent of
 * the core {@link io.gateway.oss.admin.limit.LimitStoreConfig} which
 * handles rate limiter and login limiter beans.
 */
@Configuration
public class TpmStoreConfig {

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "in_memory", matchIfMissing = true)
    static class TpmInMemoryConfig {
        @Bean
        public ClientTpmStore inMemoryClientTpmStore() {
            return new InMemoryClientTpmStore();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "redis")
    static class TpmRedisConfig {
        @Bean
        public ClientTpmStore redisClientTpmStore(StringRedisTemplate redisTemplate, SystemConfigView configView) {
            return new RedisClientTpmStore(redisTemplate, configView);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "postgresql")
    static class TpmPostgresConfig {
        @Bean
        public ClientTpmStore postgresClientTpmStore(JdbcTemplate jdbc, SystemConfigView configView) {
            return new PostgresClientTpmStore(jdbc, configView.getSharedState().getKeyPrefix());
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "hybrid")
    static class TpmHybridConfig {

        private final StringRedisTemplate redisTemplate;
        private final JdbcTemplate jdbcTemplate;
        private final SystemConfigView configView;

        TpmHybridConfig(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate,
                                SystemConfigView configView) {
            this.redisTemplate = redisTemplate;
            this.jdbcTemplate = jdbcTemplate;
            this.configView = configView;
        }

        @Bean
        public ClientTpmStore hybridClientTpmStore() {
            return BackendSelector.resolve(
                configView.getStore().getTpm(),
                InMemoryClientTpmStore::new,
                () -> new RedisClientTpmStore(redisTemplate, configView),
                () -> new PostgresClientTpmStore(jdbcTemplate, configView.getSharedState().getKeyPrefix())
            );
        }
    }
}
