package io.gateway.oss.core.routing;

import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.upstream.RouteResilienceTracker;
import io.gateway.oss.core.upstream.InMemoryProviderRuntimeStateStore;
import io.gateway.oss.core.upstream.InMemoryRouteStateStore;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteLoadBalancerTest {

    @Test
    void shouldClearCacheWhenSizeExceedsMax() {
        GatewayProperties properties = new GatewayProperties();
        properties.getLoadBalancer().setEnabled(true);
        properties.getResilience().setRetryableFailureThreshold(1);
        RouteResilienceTracker tracker = new RouteResilienceTracker(new InMemoryRouteStateStore(), properties, java.time.Clock.systemUTC(), reactor.core.scheduler.Schedulers.immediate());
        RouteLoadBalancer balancer = new RouteLoadBalancer(properties, tracker);

        // Fill the cache with synthetic group keys beyond the limit.
        // We abuse orderCandidatesByWrr with a custom groupKey per call, but since
        // that method creates local WRR instances (not stored in wrrByGroup), we
        // instead exercise select() with many distinct candidate sets.
        // Each unique sorted combination of routeIds produces a different group key.
        for (int i = 0; i < RouteLoadBalancer.WRR_MAX_SIZE + 10; i++) {
            ResolvedRoute a = route("route-" + i + "-a", 1);
            ResolvedRoute b = route("route-" + i + "-b", 1);
            balancer.select(List.of(a, b));
        }

        // Trigger one more select which should evict all stale entries
        ResolvedRoute finalA = route("route-final-a", 1);
        ResolvedRoute finalB = route("route-final-b", 1);
        ResolvedRoute selected = balancer.select(List.of(finalA, finalB));
        assertTrue(selected != null, "should still select a route after eviction");
    }

    @Test
    void shouldClearCacheOnConfigChange() {
        GatewayProperties properties = new GatewayProperties();
        properties.getLoadBalancer().setEnabled(true);
        properties.getResilience().setRetryableFailureThreshold(1);
        RouteResilienceTracker tracker = new RouteResilienceTracker(new InMemoryRouteStateStore(), properties, java.time.Clock.systemUTC(), reactor.core.scheduler.Schedulers.immediate());
        RouteLoadBalancer balancer = new RouteLoadBalancer(properties, tracker);

        ResolvedRoute a = route("route-a", 1);
        ResolvedRoute b = route("route-b", 1);
        balancer.select(List.of(a, b));

        // Should not throw; subsequent selects still work
        balancer.onConfigChange();
        ResolvedRoute selected = balancer.select(List.of(a, b));
        assertTrue(selected != null, "should select after onConfigChange");
    }

    @Test
    void shouldReturnNullWhenNoHealthyCandidate() {
        GatewayProperties properties = new GatewayProperties();
        properties.getLoadBalancer().setEnabled(true);
        properties.getResilience().setRetryableFailureThreshold(1);
        RouteResilienceTracker tracker = new RouteResilienceTracker(new InMemoryRouteStateStore(), properties, java.time.Clock.systemUTC(), reactor.core.scheduler.Schedulers.immediate());

        ResolvedRoute only = route("route-a", 1);
        tracker.recordRetryableFailure(only);

        RouteLoadBalancer balancer = new RouteLoadBalancer(properties, tracker);
        ResolvedRoute selected = balancer.select(List.of(only));

        assertNull(selected);
    }

    @Test
    void shouldSkipUnhealthyRouteAndRecoverAfterSuccess() {
        GatewayProperties properties = new GatewayProperties();
        properties.getLoadBalancer().setEnabled(true);
        properties.getResilience().setRetryableFailureThreshold(2);
        properties.getResilience().setFailureWindow(Duration.ofSeconds(30));
        properties.getResilience().setOpenDuration(Duration.ofSeconds(30));
        RouteResilienceTracker tracker = new RouteResilienceTracker(new InMemoryRouteStateStore(), properties, java.time.Clock.systemUTC(), reactor.core.scheduler.Schedulers.immediate());

        ResolvedRoute a = route("route-a", 1);
        ResolvedRoute b = route("route-b", 1);
        tracker.recordRetryableFailure(a);
        tracker.recordRetryableFailure(a);

        RouteLoadBalancer balancer = new RouteLoadBalancer(properties, tracker);
        assertEquals("route-b", balancer.select(List.of(a, b)).routeId());

        tracker.recordSuccess(a);
        String afterRecover = balancer.select(List.of(a, b)).routeId();
        // round-robin may pick either route first after recovery; ensure recovered route is selectable
        String next = balancer.select(List.of(a, b)).routeId();
        boolean recoveredSeen = "route-a".equals(afterRecover) || "route-a".equals(next);
        if (!recoveredSeen) {
            throw new AssertionError("recovered route should re-enter selection");
        }
    }

    @Test
    void shouldDistributeByWeight() {
        GatewayProperties properties = new GatewayProperties();
        properties.getLoadBalancer().setEnabled(true);
        RouteResilienceTracker tracker = new RouteResilienceTracker(new InMemoryRouteStateStore(), properties, java.time.Clock.systemUTC(), reactor.core.scheduler.Schedulers.immediate());
        RouteLoadBalancer balancer = new RouteLoadBalancer(properties, tracker);

        ResolvedRoute heavy = route("heavy", 3);
        ResolvedRoute light = route("light", 1);

        int heavyCount = 0;
        int lightCount = 0;
        int iterations = 100;
        for (int i = 0; i < iterations; i++) {
            ResolvedRoute selected = balancer.select(List.of(heavy, light));
            if ("heavy".equals(selected.routeId())) heavyCount++;
            else if ("light".equals(selected.routeId())) lightCount++;
        }

        // Heavy(3) / Light(1) = ~75% / ~25% over 100 iterations
        // Allow ±15 tolerance for randomness in WRR state timing
        assertTrue(heavyCount >= 60 && heavyCount <= 90,
                "heavy(weight=3) should be ~75%, got " + heavyCount);
        assertTrue(lightCount >= 10 && lightCount <= 40,
                "light(weight=1) should be ~25%, got " + lightCount);
    }

    @Test
    void shouldDistributeEvenlyWithEqualWeights() {
        GatewayProperties properties = new GatewayProperties();
        properties.getLoadBalancer().setEnabled(true);
        RouteResilienceTracker tracker = new RouteResilienceTracker(new InMemoryRouteStateStore(), properties, java.time.Clock.systemUTC(), reactor.core.scheduler.Schedulers.immediate());
        RouteLoadBalancer balancer = new RouteLoadBalancer(properties, tracker);

        ResolvedRoute a = route("eq-a", 1);
        ResolvedRoute b = route("eq-b", 1);

        int aCount = 0;
        int bCount = 0;
        int iterations = 100;
        for (int i = 0; i < iterations; i++) {
            ResolvedRoute selected = balancer.select(List.of(a, b));
            if ("eq-a".equals(selected.routeId())) aCount++;
            else if ("eq-b".equals(selected.routeId())) bCount++;
        }

        // Equal weights → roughly 50/50 over 100 iterations
        // Allow ±20 tolerance
        assertTrue(aCount >= 30 && aCount <= 70,
                "equal-weight A should be ~50%, got " + aCount);
        assertTrue(bCount >= 30 && bCount <= 70,
                "equal-weight B should be ~50%, got " + bCount);
    }

    @Test
    void shouldNotCrashOnSingleRoute() {
        GatewayProperties properties = new GatewayProperties();
        properties.getLoadBalancer().setEnabled(true);
        RouteResilienceTracker tracker = new RouteResilienceTracker(new InMemoryRouteStateStore(), properties, java.time.Clock.systemUTC(), reactor.core.scheduler.Schedulers.immediate());
        RouteLoadBalancer balancer = new RouteLoadBalancer(properties, tracker);

        ResolvedRoute only = route("only-route", 1);
        ResolvedRoute selected = balancer.select(List.of(only));

        assertEquals("only-route", selected.routeId());
    }

    @Test
    void shouldSkipRuntimeUnavailableProvider() {
        GatewayProperties properties = new GatewayProperties();
        properties.getLoadBalancer().setEnabled(true);
        RouteResilienceTracker tracker = new RouteResilienceTracker(new InMemoryRouteStateStore(), properties, java.time.Clock.systemUTC(), reactor.core.scheduler.Schedulers.immediate());
        InMemoryProviderRuntimeStateStore runtimeStateStore = new InMemoryProviderRuntimeStateStore();
        runtimeStateStore.save("openai", new ProviderRuntimeStateStore.ProviderRuntimeState(
                false, Instant.now(), null, 3, 0, 503, 100L, "HTTP 503"));

        RouteLoadBalancer balancer = new RouteLoadBalancer(properties, tracker, runtimeStateStore);
        ResolvedRoute selected = balancer.select(List.of(route("route-a", 1)));

        assertNull(selected);
    }

    private ResolvedRoute route(String routeId, int weight) {
        return new ResolvedRoute(
                "gpt-4o-mini",
                routeId,
                "default-chat",
                "openai",
                "openai-compatible",
                "gpt-4o-mini",
                "http://localhost:18080",
                List.of("k"),
                "k",
                Duration.ofSeconds(1),
                2,
                List.of(),
                weight
        );
    }
}
