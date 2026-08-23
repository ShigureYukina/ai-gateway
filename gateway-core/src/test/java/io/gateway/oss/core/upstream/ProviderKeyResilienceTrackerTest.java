package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderKeyResilienceTrackerTest {

    @Test
    void shouldAvoidKeyAfterThresholdThenRecoverAfterOpenDuration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), clock);
        String keySlot = "openai-primary#key-0";

        assertTrue(tracker.isAvailable(keySlot));

        tracker.recordRetryableFailure(keySlot);
        assertTrue(tracker.isAvailable(keySlot));

        tracker.recordRetryableFailure(keySlot);
        assertFalse(tracker.isAvailable(keySlot));

        clock.advance(Duration.ofSeconds(31));
        assertTrue(tracker.isAvailable(keySlot));
    }

    @Test
    void shouldResetFailureStateAfterSuccess() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), clock);
        String keySlot = "openai-primary#key-1";

        tracker.recordRetryableFailure(keySlot);
        tracker.recordSuccess(keySlot);
        tracker.recordRetryableFailure(keySlot);

        assertTrue(tracker.isAvailable(keySlot));
    }

    @Test
    void shouldCleanupStaleEntries() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), clock);

        // Record one failure (threshold is 2, so not tripped yet) on a key that will become stale
        tracker.recordRetryableFailure("stale-key");

        // Touch active key
        tracker.recordRetryableFailure("active-key");

        // Advance past idle threshold
        clock.advance(Duration.ofHours(2));

        // Refresh active key
        tracker.recordSuccess("active-key");

        // Cleanup stale entries
        tracker.cleanupStaleEntries(Duration.ofHours(1));

        // Stale key was evicted. One more failure should not trip the circuit (count resets to 1).
        // Without cleanup, 2nd failure here would trip it.
        tracker.recordRetryableFailure("stale-key");
        assertTrue(tracker.isAvailable("stale-key"));
    }

    @Test
    void shouldNotCleanupEntriesWithOpenCircuit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        // Use a long openDuration so circuit stays open past idle threshold
        GatewayProperties props = properties();
        props.getResilience().setOpenDuration(Duration.ofHours(2));
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(props, clock);

        // Trip the circuit breaker
        tracker.recordRetryableFailure("blocked-key");
        tracker.recordRetryableFailure("blocked-key");
        assertFalse(tracker.isAvailable("blocked-key"));

        // Advance past idle threshold (1h) but NOT past openDuration (2h)
        clock.advance(Duration.ofMinutes(90));

        // Cleanup — blocked-key is stale but circuit is still open, so it should NOT be removed
        tracker.cleanupStaleEntries(Duration.ofHours(1));

        // Circuit should still be open (entry was preserved)
        assertFalse(tracker.isAvailable("blocked-key"));
    }

    @Test
    void shouldHandleConcurrentFailureRecordingWithoutDataLoss() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), clock);
        String keySlot = "concurrent-key";

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
                        tracker.recordRetryableFailure(keySlot);
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

        // With threshold=2, any single failure should have tripped the circuit
        assertFalse(tracker.isAvailable(keySlot));

        // After openDuration, circuit should recover cleanly
        clock.advance(Duration.ofSeconds(31));
        assertTrue(tracker.isAvailable(keySlot));
    }

    @Test
    void shouldReopenCircuitWhenFailureOccursAfterOpenDurationRecovery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), clock);
        String keySlot = "probe-key";

        // Trip the circuit
        tracker.recordRetryableFailure(keySlot);
        tracker.recordRetryableFailure(keySlot);
        assertFalse(tracker.isAvailable(keySlot));

        // Wait for openDuration to expire → circuit recovers
        clock.advance(Duration.ofSeconds(31));
        assertTrue(tracker.isAvailable(keySlot));

        // A single failure after recovery should NOT immediately re-open (threshold=2)
        tracker.recordRetryableFailure(keySlot);
        assertTrue(tracker.isAvailable(keySlot));

        // Second failure should re-open the circuit
        tracker.recordRetryableFailure(keySlot);
        assertFalse(tracker.isAvailable(keySlot));
    }

    @Test
    void shouldIgnoreFailuresOutsideConfiguredFailureWindowForKeyTracker() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-27T00:00:00Z"));
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), clock);
        String keySlot = "window-key";

        // First failure
        tracker.recordRetryableFailure(keySlot);
        assertTrue(tracker.isAvailable(keySlot));

        // Advance past failureWindow (30s)
        clock.advance(Duration.ofSeconds(31));

        // Second failure — should NOT trip because the first one expired
        tracker.recordRetryableFailure(keySlot);
        assertTrue(tracker.isAvailable(keySlot));
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().setRetryableFailureThreshold(2);
        properties.getResilience().setFailureWindow(Duration.ofSeconds(30));
        properties.getResilience().setOpenDuration(Duration.ofSeconds(30));
        return properties;
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
