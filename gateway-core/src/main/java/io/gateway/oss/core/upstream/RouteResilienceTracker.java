package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.routing.RouteHealthChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;

public class RouteResilienceTracker implements RouteHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(RouteResilienceTracker.class);

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

    // 状态写入是异步 fire-and-forget：record* 在上游响应线程（事件循环）上被调用，
    // 而 Redis/JDBC 版 RouteStateStore 是阻塞实现，直接调用会阻塞事件循环（审查 C1）。
    // 韧性状态允许毫秒级陈旧。
    public void recordSuccess(ResolvedRoute route) {
        Mono.fromRunnable(() -> routeStateStore.recordSuccess(route.routeId()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.warn("route_state_record_failed routeId={} cause={}", route.routeId(), e.toString()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    public void recordRetryableFailure(ResolvedRoute route) {
        Mono.fromRunnable(() -> routeStateStore.recordRetryableFailure(route.routeId(), now(), properties.getResilience()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.warn("route_state_record_failed routeId={} cause={}", route.routeId(), e.toString()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
