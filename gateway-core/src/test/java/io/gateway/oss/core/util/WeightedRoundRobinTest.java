package io.gateway.oss.core.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeightedRoundRobinTest {

    @Test
    void shouldDistributeSelectionsByConfiguredWeights() {
        WeightedRoundRobin<String> wrr = new WeightedRoundRobin<>(List.of(
                new WeightedRoundRobin.WeightedItem<>("route-a", 3),
                new WeightedRoundRobin.WeightedItem<>("route-b", 1)
        ));

        Map<String, Integer> counts = new HashMap<>();
        counts.put("route-a", 0);
        counts.put("route-b", 0);

        for (int i = 0; i < 40; i++) {
            String selected = wrr.select();
            counts.computeIfPresent(selected, (k, v) -> v + 1);
        }

        assertEquals(30, counts.get("route-a"));
        assertEquals(10, counts.get("route-b"));
    }

    @Test
    void select_emptyList_returnsNull() {
        assertThrows(IllegalArgumentException.class, () -> new WeightedRoundRobin<>(List.of()));
    }

    @Test
    void select_singleItem_alwaysReturnsIt() {
        WeightedRoundRobin<String> wrr = new WeightedRoundRobin<>(List.of(
                new WeightedRoundRobin.WeightedItem<>("only-route", 5)
        ));

        for (int i = 0; i < 20; i++) {
            assertEquals("only-route", wrr.select());
        }
    }

    @Test
    void select_respectsWeights() {
        WeightedRoundRobin<String> wrr = new WeightedRoundRobin<>(List.of(
                new WeightedRoundRobin.WeightedItem<>("a", 5),
                new WeightedRoundRobin.WeightedItem<>("b", 3),
                new WeightedRoundRobin.WeightedItem<>("c", 2)
        ));

        Map<String, Integer> counts = new HashMap<>();
        counts.put("a", 0);
        counts.put("b", 0);
        counts.put("c", 0);

        int totalCycles = 100;
        for (int i = 0; i < totalCycles; i++) {
            String selected = wrr.select();
            counts.computeIfPresent(selected, (k, v) -> v + 1);
        }

        assertEquals(50, counts.get("a"));
        assertEquals(30, counts.get("b"));
        assertEquals(20, counts.get("c"));
    }

    @Test
    void select_threadSafe_concurrentAccess() throws InterruptedException {
        WeightedRoundRobin<String> wrr = new WeightedRoundRobin<>(List.of(
                new WeightedRoundRobin.WeightedItem<>("x", 1),
                new WeightedRoundRobin.WeightedItem<>("y", 1)
        ));

        int threadCount = 8;
        int selectionsPerThread = 500;
        ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
        counts.put("x", 0);
        counts.put("y", 0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < selectionsPerThread; i++) {
                        String selected = wrr.select();
                        counts.compute(selected, (k, v) -> v == null ? 1 : v + 1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        int totalSelections = counts.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(threadCount * selectionsPerThread, totalSelections);
    }
}
