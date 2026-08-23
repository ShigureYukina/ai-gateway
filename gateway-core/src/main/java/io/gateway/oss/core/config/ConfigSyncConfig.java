package io.gateway.oss.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers {@link ConfigSyncPublisher} when Redis is available.
 * <p>
 * When Redis is not on the classpath or not connected, this configuration
 * is skipped and DynamicConfigService operates without pub/sub sync.
 * </p>
 */
@Configuration
@ConditionalOnBean(StringRedisTemplate.class)
public class ConfigSyncConfig {

    @Bean
    public ConfigSyncPublisher configSyncPublisher(StringRedisTemplate redisTemplate,
                                                    GatewayProperties properties,
                                                    ConfigLoadService configLoadService) {
        return new ConfigSyncPublisher(redisTemplate, properties, configLoadService);
    }
}
