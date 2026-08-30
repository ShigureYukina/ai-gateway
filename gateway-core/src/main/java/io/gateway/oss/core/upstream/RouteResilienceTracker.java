package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.routing.RouteHealthChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;

public class RouteResilienceTracker implements RouteHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(RouteResilienceTracker.class);

    private final GatewayProperties properties;
    private final Clock clock;
    private final RouteStateStore routeStateStore;
    private final Scheduler stateScheduler;

    public RouteResilienceTracker(RouteStateStore routeStateStore, GatewayProperties properties) {
        this(routeStateStore, properties, Clock.systemUTC(), Schedulers.boundedElastic());
    }

    RouteResilienceTracker(GatewayProperties properties, Clock clock) {
        this(new InMemoryRouteStateStore(), properties, clock, Schedulers.boundedElastic());
    }

    RouteResilienceTracker(RouteStateStore routeStateStore, GatewayProperties properties, Clock clock) {
        this(routeStateStore, properties, clock, Schedulers.boundedElastic());
    }

    /**
     * 测试注入点：传 {@link Schedulers#immediate()} 时 record* 同步落状态，
     * 消除 fire-and-forget 异步写入带来的断言竞态。
     */
    public RouteResilienceTracker(RouteStateStore routeStateStore, GatewayProperties properties,
                                  Clock clock, Scheduler stateScheduler) {
        this.routeStateStore = routeStateStore;
        this.properties = properties;
        this.clock = clock;
        this.stateScheduler = stateScheduler;
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
                .subscribeOn(stateScheduler)
                .doOnError(e -> log.warn("route_state_record_failed routeId={} cause={}", route.routeId(), e.toString()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    public void recordRetryableFailure(ResolvedRoute route) {
        Mono.fromRunnable(() -> routeStateStore.recordRetryableFailure(route.routeId(), now(), properties.getResilience()))
                .subscribeOn(stateScheduler)
                .doOnError(e -> log.warn("route_state_record_failed routeId={} cause={}", route.routeId(), e.toString()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
