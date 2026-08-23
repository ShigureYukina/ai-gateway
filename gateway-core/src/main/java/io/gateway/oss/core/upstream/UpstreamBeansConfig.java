package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UpstreamBeansConfig {
    @Bean
    public RouteResilienceTracker routeResilienceTracker(RouteStateStore routeStateStore, GatewayProperties properties) {
        return new RouteResilienceTracker(routeStateStore, properties);
    }
}
