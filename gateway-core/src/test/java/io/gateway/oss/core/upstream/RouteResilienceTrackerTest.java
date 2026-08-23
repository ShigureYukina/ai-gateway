package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteResilienceTrackerTest {

    @Test
    void shouldOpenRouteAfterThresholdAndRecoverAfterWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        RouteResilienceTracker tracker = new RouteResilienceTracker(properties(), clock);
        ResolvedRoute route = route("openai-primary");

        assertTrue(tracker.isAvailable(route));

        tracker.recordRetryableFailure(route);
        assertTrue(tracker.isAvailable(route));

        tracker.recordRetryableFailure(route);
        assertFalse(tracker.isAvailable(route));

        clock.advance(Duration.ofSeconds(31));
        assertTrue(tracker.isAvailable(route));
    }

    @Test
    void shouldIgnoreFailuresOutsideConfiguredFailureWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        RouteResilienceTracker tracker = new RouteResilienceTracker(properties(), clock);
        ResolvedRoute route = route("openai-primary");

        tracker.recordRetryableFailure(route);
        clock.advance(Duration.ofSeconds(31));
        tracker.recordRetryableFailure(route);

        assertTrue(tracker.isAvailable(route));
    }

    @Test
    void shouldResetFailureHistoryAfterSuccess() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        RouteResilienceTracker tracker = new RouteResilienceTracker(properties(), clock);
        ResolvedRoute route = route("openai-primary");

        tracker.recordRetryableFailure(route);
        tracker.recordSuccess(route);
        tracker.recordRetryableFailure(route);

        assertTrue(tracker.isAvailable(route));
    }

    @Test
    void shouldHandleConcurrentFailureRecordingWithoutDataLoss() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        RouteResilienceTracker tracker = new RouteResilienceTracker(properties(), clock);
        ResolvedRoute route = route("concurrent-route");

        int threadCount = 10;
        int failuresPerThread = 5;
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < failuresPerThread; i++) {
                        tracker.recordRetryableFailure(route);
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS), "Threads did not finish in time");
        if (error.get() != null) {
            throw new AssertionError("Concurrent recording threw", error.get());
        }

        // With threshold=2, circuit should be open
        assertFalse(tracker.isAvailable(route));

        // After openDuration, circuit should recover cleanly
        clock.advance(Duration.ofSeconds(31));
        assertTrue(tracker.isAvailable(route));
    }

    @Test
    void shouldReopenCircuitWhenFailureOccursAfterOpenDurationRecovery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        RouteResilienceTracker tracker = new RouteResilienceTracker(properties(), clock);
        ResolvedRoute route = route("probe-route");

        // Trip the circuit
        tracker.recordRetryableFailure(route);
        tracker.recordRetryableFailure(route);
        assertFalse(tracker.isAvailable(route));

        // Wait for openDuration to expire → circuit recovers
        clock.advance(Duration.ofSeconds(31));
        assertTrue(tracker.isAvailable(route));

        // A single failure after recovery should NOT immediately re-open (threshold=2)
        tracker.recordRetryableFailure(route);
        assertTrue(tracker.isAvailable(route));

        // Second failure should re-open the circuit
        tracker.recordRetryableFailure(route);
        assertFalse(tracker.isAvailable(route));
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().setRetryableFailureThreshold(2);
        properties.getResilience().setFailureWindow(Duration.ofSeconds(30));
        properties.getResilience().setOpenDuration(Duration.ofSeconds(30));
        return properties;
    }

    private ResolvedRoute route(String routeId) {
        return new ResolvedRoute(
                "gpt-4o-mini",
                routeId,
                "default-chat",
                "openai",
                "openai-compatible",
                "gpt-4o-mini",
                "http://localhost:18080",
                "test-key",
                Duration.ofSeconds(1),
                2,
                List.of()
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
