package io.gateway.oss.core.routing;

import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.routing.RouteHealthChecker;
import io.gateway.oss.core.contract.routing.RouteSelector;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.upstream.InMemoryProviderRuntimeStateStore;
import io.gateway.oss.core.upstream.ProviderRuntimeStateStore;
import io.gateway.oss.core.util.WeightedRoundRobin;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Route-level weighted round-robin load balancer.
 *
 * When load-balancer is enabled and a scene has multiple weighted routes,
 * this component selects among healthy routes using WRR to distribute
 * traffic proportionally to configured weights.
 *
 * When disabled or when all routes have equal weight (default 1),
 * behavior degrades to equal-weight round-robin which is compatible
 * with the legacy sequential-fallback ordering.
 *
 * Streaming behavior: load balancing applies to the initial route selection
 * for both streaming and non-streaming. However, streaming does NOT support
 * automatic fallback (per existing contract). If the WRR-selected streaming
 * route fails, the error propagates directly to the client.
 */
@Component
public class RouteLoadBalancer implements RouteSelector {

    /** Maximum entries in the WRR cache before a full clear is triggered. */
    static final int WRR_MAX_SIZE = 500;

    private final GatewayProperties properties;
    private final RouteHealthChecker healthChecker;
    private final ProviderRuntimeStateStore providerRuntimeStateStore;
    private final ConcurrentHashMap<String, WeightedRoundRobin<String>> wrrByGroup = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RouteLoadBalancer(GatewayProperties properties,
                             RouteHealthChecker healthChecker,
                             ProviderRuntimeStateStore providerRuntimeStateStore) {
        this.properties = properties;
        this.healthChecker = healthChecker;
        this.providerRuntimeStateStore = providerRuntimeStateStore;
    }

    public RouteLoadBalancer(GatewayProperties properties, RouteHealthChecker healthChecker) {
        this(properties, healthChecker, new InMemoryProviderRuntimeStateStore());
    }

    /**
     * Called when route configuration changes. Clears the WRR cache so that
     * stale group keys from the previous configuration are evicted.
     */
    public void onConfigChange() {
        wrrByGroup.clear();
    }

    /**
     * Select a route from the candidate pool using WRR.
     * Only healthy routes are considered. Returns null if no healthy candidate exists.
     *
     * @param candidateRoutes all candidate routes (primary + fallbacks) with their weights
     * @return the selected route, or null if none healthy
     */
    public ResolvedRoute select(List<ResolvedRoute> candidateRoutes) {
        if (candidateRoutes == null || candidateRoutes.isEmpty()) {
            return null;
        }

        List<ResolvedRoute> healthy = healthyCandidates(candidateRoutes);
        if (healthy.isEmpty()) {
            return null;
        }

        if (healthy.size() == 1) {
            return healthy.get(0);
        }

        // Build a deterministic group key from the sorted route IDs
        String groupKey = buildGroupKey(candidateRoutes);

        // Lazy eviction: prevent unbounded growth from stale group keys
        if (wrrByGroup.size() > WRR_MAX_SIZE) {
            wrrByGroup.clear();
        }

        // Rebuild WRR if the candidate set changed
        wrrByGroup.compute(groupKey, (key, existing) -> {
            List<String> routeIds = healthy.stream().map(ResolvedRoute::routeId).toList();
            if (existing != null && matchesExisting(existing, routeIds)) {
                return existing;
            }
            List<WeightedRoundRobin.WeightedItem<String>> items = healthy.stream()
                    .map(r -> new WeightedRoundRobin.WeightedItem<>(r.routeId(), Math.max(1, r.weight())))
                    .toList();
            return new WeightedRoundRobin<>(items);
        });

        WeightedRoundRobin<String> wrr = wrrByGroup.get(groupKey);
        if (wrr == null) {
            return healthy.get(0);
        }

        String selectedRouteId = wrr.select();
        return candidateRoutes.stream()
                .filter(r -> r.routeId().equals(selectedRouteId))
                .findFirst()
                .orElse(healthy.get(0));
    }

    /**
     * Order fallback candidates using WRR selection.
     * Returns a new list with candidates ordered by WRR priority.
     * The first element is the WRR-selected best candidate.
     *
     * @param healthyCandidates pre-filtered healthy fallback candidates
     * @param groupKey stable identifier for this candidate set (e.g., scene name)
     * @return candidates reordered by WRR priority
     */
    public List<ResolvedRoute> orderCandidatesByWrr(List<ResolvedRoute> healthyCandidates, String groupKey) {
        if (healthyCandidates == null || healthyCandidates.size() <= 1) {
            return healthyCandidates == null ? List.of() : healthyCandidates;
        }

        List<ResolvedRoute> result = new ArrayList<>(healthyCandidates.size());
        List<ResolvedRoute> remaining = new ArrayList<>(healthyCandidates);

        while (!remaining.isEmpty()) {
            if (remaining.size() == 1) {
                result.add(remaining.get(0));
                break;
            }

            String subGroupKey = groupKey + ":" + buildGroupKey(remaining);
            List<WeightedRoundRobin.WeightedItem<String>> items = remaining.stream()
                    .map(r -> new WeightedRoundRobin.WeightedItem<>(r.routeId(), Math.max(1, r.weight())))
                    .toList();

            WeightedRoundRobin<String> wrr = new WeightedRoundRobin<>(items);
            String selectedId = wrr.select();

            ResolvedRoute selected = remaining.stream()
                    .filter(r -> r.routeId().equals(selectedId))
                    .findFirst()
                    .orElse(remaining.get(0));
            result.add(selected);
            remaining.remove(selected);
        }

        return result;
    }

    public boolean isEnabled() {
        return properties.getLoadBalancer().isEnabled();
    }

    private List<ResolvedRoute> healthyCandidates(List<ResolvedRoute> candidates) {
        return candidates.stream()
                .filter(this::isRouteEnabled)
                .filter(this::isProviderRuntimeAvailable)
                .filter(r -> healthChecker.isAvailable(r.routeId()))
                .toList();
    }

    private boolean isProviderRuntimeAvailable(ResolvedRoute route) {
        return providerRuntimeStateStore.get(route.provider()).runtimeAvailable();
    }

    private boolean isRouteEnabled(ResolvedRoute route) {
        RouteConfig config = properties.getRoutes().get(route.routeId());
        return config == null || config.isEnabled();
    }

    private String buildGroupKey(List<ResolvedRoute> routes) {
        return routes.stream()
                .map(ResolvedRoute::routeId)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("empty");
    }

    private boolean matchesExisting(WeightedRoundRobin<String> existing, List<String> routeIds) {
        if (existing.items().size() != routeIds.size()) {
            return false;
        }
        List<String> existingIds = existing.items().stream()
                .map(WeightedRoundRobin.WeightedItem::value)
                .toList();
        return existingIds.equals(routeIds);
    }
}
