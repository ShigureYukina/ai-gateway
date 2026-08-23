package io.gateway.oss.core.web;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.Backend;
import io.gateway.oss.core.config.SharedStateConfig;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class GatewayHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final SharedStateConfig sharedStateConfig;

    public GatewayHealthIndicator(
            @Lazy org.springframework.beans.factory.ObjectProvider<DataSource> dataSourceProvider,
            @Lazy org.springframework.beans.factory.ObjectProvider<RedisConnectionFactory> redisFactoryProvider,
            GatewayProperties properties) {
        this.dataSource = dataSourceProvider.getIfAvailable();
        this.redisConnectionFactory = redisFactoryProvider.getIfAvailable();
        this.sharedStateConfig = properties.getSharedState();
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        boolean allUp = true;

        // Check PostgreSQL
        if (dataSource != null) {
            try (Connection conn = dataSource.getConnection()) {
                if (conn.isValid(2)) {
                    builder.withDetail("postgresql", "UP");
                } else {
                    builder.withDetail("postgresql", "DOWN");
                    allUp = false;
                }
            } catch (Exception e) {
                builder.withDetail("postgresql", "DOWN: " + e.getMessage());
                allUp = false;
            }
        } else {
            builder.withDetail("postgresql", "NOT_CONFIGURED");
        }

        // Check Redis (only when backend is REDIS)
        if (sharedStateConfig != null && sharedStateConfig.getBackend() == Backend.REDIS) {
            if (redisConnectionFactory != null) {
                try {
                    var pong = redisConnectionFactory.getConnection().ping();
                    if ("PONG".equals(pong)) {
                        builder.withDetail("redis", "UP");
                    } else {
                        builder.withDetail("redis", "DOWN: unexpected response");
                        allUp = false;
                    }
                } catch (Exception e) {
                    builder.withDetail("redis", "DOWN: " + e.getMessage());
                    allUp = false;
                }
            } else {
                builder.withDetail("redis", "DOWN: no connection factory");
                allUp = false;
            }
        } else {
            builder.withDetail("redis", "NOT_CONFIGURED (in-memory backend)");
        }

        return allUp ? builder.build() : builder.down().build();
    }
}
