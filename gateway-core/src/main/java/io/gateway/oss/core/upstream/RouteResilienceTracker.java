package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.routing.RouteHealthChecker;
import java.time.Clock;
import java.time.Instant;

public class RouteResilienceTracker implements RouteHealthChecker {

    private final GatewayProperties properties;
    private final Clock clock;
    private final RouteStateStore routeStateStore;

    public RouteResilienceTracker(RouteStateStore routeStateStore, GatewayProperties properties) {
        this(routeStateStore, properties, Clock.systemUTC());
    }

    RouteResilienceTracker(GatewayProperties properties, Clock clock) {
        this(new InMemoryRouteStateStore(), properties, clock);
    }

    RouteResilienceTracker(RouteStateStore routeStateStore, GatewayProperties properties, Clock clock) {
        this.routeStateStore = routeStateStore;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isAvailable(ResolvedRoute route) {
        return routeStateStore.isAvailable(route.routeId(), now());
    }

    public boolean isAvailable(String routeId) {
        return routeStateStore.isAvailable(routeId, now());
    }

    public void recordSuccess(ResolvedRoute route) {
        routeStateStore.recordSuccess(route.routeId());
    }

    public void recordRetryableFailure(ResolvedRoute route) {
        routeStateStore.recordRetryableFailure(route.routeId(), now(), properties.getResilience());
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
