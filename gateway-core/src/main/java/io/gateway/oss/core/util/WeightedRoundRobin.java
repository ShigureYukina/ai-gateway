package io.gateway.oss.core.util;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Smooth Weighted Round-Robin selector (Nginx-style, lock-free).
 * Distributes selections proportionally to configured weights over each full cycle.
 * Thread-safe for concurrent use without blocking.
 *
 * @param <T> the type of items being selected
 */
public class WeightedRoundRobin<T> {

    private final List<WeightedItem<T>> items;
    private final int totalWeight;
    private final AtomicReference<int[]> currentWeights;

    public WeightedRoundRobin(List<WeightedItem<T>> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Weighted items must not be empty");
        }
        this.items = List.copyOf(items);
        int sum = 0;
        for (WeightedItem<T> item : items) {
            if (item.weight() <= 0) {
                throw new IllegalArgumentException("Weight must be positive: " + item.weight());
            }
            sum += item.weight();
        }
        this.totalWeight = sum;
        this.currentWeights = new AtomicReference<>(new int[items.size()]);
    }

    /**
     * Select the next item using smooth weighted round-robin.
     * Lock-free: uses CAS on the entire weight array as a single atomic unit.
     * Each call advances the internal state by one step.
     */
    public T select() {
        int n = items.size();

        while (true) {
            int[] snapshot = currentWeights.get();
            int[] updated = new int[n];

            int bestIndex = -1;
            int bestWeight = Integer.MIN_VALUE;

            for (int i = 0; i < n; i++) {
                int newW = snapshot[i] + items.get(i).weight();
                updated[i] = newW;
                if (newW > bestWeight) {
                    bestWeight = newW;
                    bestIndex = i;
                }
            }

            if (bestIndex >= 0) {
                updated[bestIndex] -= totalWeight;
            }

            if (currentWeights.compareAndSet(snapshot, updated)) {
                return items.get(bestIndex).value();
            }
        }
    }

    public List<WeightedItem<T>> items() {
        return items;
    }

    public int totalWeight() {
        return totalWeight;
    }

    public record WeightedItem<T>(T value, int weight) {}
}
