package io.gateway.oss.core.upstream;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.contract.routing.ProviderApiKeyPool;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.util.WeightedRoundRobin;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ProviderKeySelector {

    private final ProviderKeyResilienceTracker resilienceTracker;
    private final ConcurrentHashMap<String, AtomicInteger> routeCursor = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WeightedRoundRobin<Integer>> weightedSelectors = new ConcurrentHashMap<>();

    public ProviderKeySelector(ProviderKeyResilienceTracker resilienceTracker) {
        this.resilienceTracker = resilienceTracker;
    }

    public SelectedProviderKey select(ResolvedRoute route) {
        ProviderApiKeyPool pool = route.providerApiKeyPool();
        List<String> keys = pool.keys().isEmpty() ? route.providerApiKeys() : pool.keys();
        if (keys == null || keys.isEmpty()) {
            String fallbackKey = route.providerApiKey();
            if (fallbackKey == null || fallbackKey.isBlank()) {
                throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "config_error", "Provider api key is not configured");
            }
            return new SelectedProviderKey(fallbackKey, route.routeId() + "#key-0", 0, false);
        }

        List<Integer> weights = pool.weights();
        if (hasEffectiveWeights(keys, weights)) {
            SelectedProviderKey weighted = selectWeighted(route, keys, weights);
            if (weighted != null) {
                return weighted;
            }
        }

        AtomicInteger cursor = routeCursor.computeIfAbsent(route.routeId(), ignored -> new AtomicInteger(0));
        int size = keys.size();
        int start = Math.floorMod(cursor.getAndIncrement(), size);

        for (int i = 0; i < size; i++) {
            int index = (start + i) % size;
            String keySlotId = slotId(route.routeId(), index);
            if (!resilienceTracker.isAvailable(keySlotId)) {
                continue;
            }
            return new SelectedProviderKey(keys.get(index), keySlotId, index, true);
        }

        int fallbackIndex = start;
        return new SelectedProviderKey(keys.get(fallbackIndex), slotId(route.routeId(), fallbackIndex), fallbackIndex, true);
    }

    private SelectedProviderKey selectWeighted(ResolvedRoute route, List<String> keys, List<Integer> weights) {
        WeightedRoundRobin<Integer> selector = weightedSelectors.compute(route.routeId(), (ignored, existing) -> {
            List<WeightedRoundRobin.WeightedItem<Integer>> items = weightedItems(keys, weights);
            if (existing != null && matches(existing, items)) {
                return existing;
            }
            return new WeightedRoundRobin<>(items);
        });
        if (selector == null) {
            return null;
        }
        for (int i = 0; i < keys.size(); i++) {
            int index = selector.select();
            String keySlotId = slotId(route.routeId(), index);
            if (!resilienceTracker.isAvailable(keySlotId)) {
                continue;
            }
            return new SelectedProviderKey(keys.get(index), keySlotId, index, true);
        }
        return null;
    }

    private List<WeightedRoundRobin.WeightedItem<Integer>> weightedItems(List<String> keys, List<Integer> weights) {
        java.util.ArrayList<WeightedRoundRobin.WeightedItem<Integer>> items = new java.util.ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            int weight = 1;
            if (weights != null && i < weights.size() && weights.get(i) != null && weights.get(i) > 0) {
                weight = weights.get(i);
            }
            items.add(new WeightedRoundRobin.WeightedItem<>(i, weight));
        }
        return items;
    }

    private boolean matches(WeightedRoundRobin<Integer> existing, List<WeightedRoundRobin.WeightedItem<Integer>> items) {
        if (existing.items().size() != items.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            WeightedRoundRobin.WeightedItem<Integer> current = existing.items().get(i);
            WeightedRoundRobin.WeightedItem<Integer> next = items.get(i);
            if (!current.value().equals(next.value()) || current.weight() != next.weight()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEffectiveWeights(List<String> keys, List<Integer> weights) {
        if (keys == null || keys.size() <= 1 || weights == null || weights.isEmpty()) {
            return false;
        }
        int first = normalizedWeight(weights, 0);
        for (int i = 1; i < keys.size(); i++) {
            if (normalizedWeight(weights, i) != first) {
                return true;
            }
        }
        return false;
    }

    private int normalizedWeight(List<Integer> weights, int index) {
        if (weights == null || index >= weights.size() || weights.get(index) == null || weights.get(index) <= 0) {
            return 1;
        }
        return weights.get(index);
    }

    public record SelectedProviderKey(String keyValue, String keySlotId, int keyIndex, boolean fromPool) {
    }

    private String slotId(String routeId, int index) {
        return routeId + "#key-" + index;
    }

}
