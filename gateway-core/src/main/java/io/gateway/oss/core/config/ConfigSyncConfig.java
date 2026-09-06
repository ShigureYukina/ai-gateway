package io.gateway.oss.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers {@link ConfigSyncPublisher} when Redis is available.
 * <p>
 * When Redis is not on the classpath or not connected, this configuration
 * is skipped and DynamicConfigService operates without pub/sub sync.
 * Set {@code gateway.config.sync.enabled=false} to opt out explicitly
 * （单节点集成测试共享同一个 Redis 时，跨上下文 pub/sub 会互相覆盖配置）.
 * </p>
 */
@Configuration
@ConditionalOnBean(StringRedisTemplate.class)
@ConditionalOnProperty(name = "gateway.config.sync.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigSyncConfig {

    @Bean
    public ConfigSyncPublisher configSyncPublisher(StringRedisTemplate redisTemplate,
                                                    GatewayProperties properties,
                                                    ConfigLoadService configLoadService) {
        return new ConfigSyncPublisher(redisTemplate, properties, configLoadService);
    }
}
