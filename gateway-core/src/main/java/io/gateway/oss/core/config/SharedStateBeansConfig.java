package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.ConfigAuditStore;
import io.gateway.oss.core.contract.ConfigVersionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared beans that depend on ConfigStore —
 * activated for any backend (in_memory, redis, postgresql).
 * <p>
 * ConfigAuditStore and ConfigVersionStore are optional — injected via
 * ObjectProvider so that {@code gateway-admin}'s implementations can
 * be absent when running in core-only mode.
 */
@Configuration
public class SharedStateBeansConfig {

    @Bean
    public DynamicConfigService dynamicConfigService(ConfigStore configStore,
                                                        GatewayProperties properties,
                                                        ObjectMapper objectMapper,
                                                        ObjectProvider<ConfigAuditStore> auditStoreProvider,
                                                        ObjectProvider<ConfigVersionStore> versionStoreProvider,
                                                        ObjectProvider<io.gateway.oss.core.routing.RouteLoadBalancer> routeLoadBalancerProvider,
                                                        ObjectProvider<io.gateway.oss.core.upstream.Resilience4jCircuitBreakerService> resilienceServiceProvider,
                                                        ObjectProvider<io.gateway.oss.core.routing.ModelRouteResolver> modelRouteResolverProvider,
                                                        ObjectProvider<ConfigSyncPublisher> syncPublisherProvider,
                                                        ConfigLoadService configLoadService) {
        return new DynamicConfigService(configStore, properties, objectMapper,
                auditStoreProvider.getIfAvailable(),
                versionStoreProvider.getIfAvailable(),
                configLoadService,
                routeLoadBalancerProvider,
                resilienceServiceProvider,
                modelRouteResolverProvider,
                syncPublisherProvider);
    }
}
