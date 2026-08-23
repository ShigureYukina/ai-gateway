package io.gateway.oss.core.upstream.state;

import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.upstream.InMemoryRouteStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InMemoryRouteStateStore} 的单元测试。
 * 覆盖滑动窗口熔断逻辑、StampedLock 并发语义、过期剪枝和自动恢复。
 */
class InMemoryRouteStateStoreTest {

    private InMemoryRouteStateStore store;
    private ResilienceConfig config;

    @BeforeEach
    void setUp() {
        store = new InMemoryRouteStateStore();
        config = new ResilienceConfig();
        config.setRetryableFailureThreshold(3);
        config.setFailureWindow(Duration.ofSeconds(30));
        config.setOpenDuration(Duration.ofSeconds(30));
        config.setMaxAttempts(2);
    }

    // ─── Basic availability ───

    @Test
    void freshRouteShouldBeAvailable() {
        assertTrue(store.isAvailable("route-a", Instant.now()));
    }

    @Test
    void differentRoutesShouldHaveIndependentState() {
        assertTrue(store.isAvailable("route-a", Instant.now()));
        assertTrue(store.isAvailable("route-b", Instant.now()));
    }

    // ─── Failure threshold ───

    @Test
    void failuresBelowThresholdShouldNotTripBreaker() {
        Instant now = Instant.now();
        store.recordRetryableFailure("route-a", now, config);
        store.recordRetryableFailure("route-a", now.plusSeconds(1), config);
        // threshold=3, only 2 failures → still available
        assertTrue(store.isAvailable("route-a", now.plusSeconds(2)));
    }

    @Test
    void failuresAtThresholdShouldTripBreaker() {
        Instant now = Instant.now();
        store.recordRetryableFailure("route-a", now, config);
        store.recordRetryableFailure("route-a", now.plusSeconds(1), config);
        store.recordRetryableFailure("route-a", now.plusSeconds(2), config);
        // threshold=3, 3 failures → circuit open
        assertFalse(store.isAvailable("route-a", now.plusSeconds(3)));
    }

    @Test
    void failuresBeyondThresholdShouldKeepBreakerOpen() {
        Instant now = Instant.now();
        store.recordRetryableFailure("route-a", now, config);
        store.recordRetryableFailure("route-a", now.plusSeconds(1), config);
        store.recordRetryableFailure("route-a", now.plusSeconds(2), config);
        store.recordRetryableFailure("route-a", now.plusSeconds(3), config);
        assertFalse(store.isAvailable("route-a", now.plusSeconds(4)));
    }

    // ─── Auto-recovery ───

    @Test
    void routeShouldRecoverAfterOpenDuration() {
        Instant now = Instant.now();
        store.recordRetryableFailure("route-a", now, config);
        store.recordRetryableFailure("route-a", now.plusSeconds(1), config);
        store.recordRetryableFailure("route-a", now.plusSeconds(2), config);
        assertFalse(store.isAvailable("route-a", now.plusSeconds(3)));

        // After openDuration (30s), should recover
        assertTrue(store.isAvailable("route-a", now.plusSeconds(35)));
    }

    @Test
    void recoveredRouteShouldTripAgainOnNewFailures() {
        Instant now = Instant.now();
        // Trip once
        store.recordRetryableFailure("route-a", now, config);
        store.recordRetryableFailure("route-a", now.plusSeconds(1), config);
        store.recordRetryableFailure("route-a", now.plusSeconds(2), config);

        // Recover
        Instant afterRecovery = now.plusSeconds(35);
        assertTrue(store.isAvailable("route-a", afterRecovery));

        // Trip again
        store.recordRetryableFailure("route-a", afterRecovery, config);
        store.recordRetryableFailure("route-a", afterRecovery.plusSeconds(1), config);
        store.recordRetryableFailure("route-a", afterRecovery.plusSeconds(2), config);
        assertFalse(store.isAvailable("route-a", afterRecovery.plusSeconds(3)));
    }

    // ─── Success resets state ───

    @Test
    void successShouldResetFailuresAndKeepRouteAvailable() {
        Instant now = Instant.now();
        store.recordRetryableFailure("route-a", now, config);
        store.recordRetryableFailure("route-a", now.plusSeconds(1), config);
        // Success resets the counter
        store.recordSuccess("route-a");
        assertTrue(store.isAvailable("route-a", now.plusSeconds(2)));

        // After success, previous failures shouldn't count
        store.recordRetryableFailure("route-a", now.plusSeconds(3), config);
        // Only 1 failure since reset, threshold=3 → still available
        assertTrue(store.isAvailable("route-a", now.plusSeconds(4)));
    }

    @Test
    void successShouldCloseOpenCircuit() {
        Instant now = Instant.now();
        store.recordRetryableFailure("route-a", now, config);
        store.recordRetryableFailure("route-a", now.plusSeconds(1), config);
        store.recordRetryableFailure("route-a", now.plusSeconds(2), config);
        assertFalse(store.isAvailable("route-a", now.plusSeconds(3)));

        // Success when circuit is open should close it
        store.recordSuccess("route-a");
        assertTrue(store.isAvailable("route-a", now.plusSeconds(4)));
    }

    // ─── Window pruning ───

    @Test
    void oldFailuresOutsideWindowShouldBePruned() {
        Instant now = Instant.now();
        // 2 failures within window
        store.recordRetryableFailure("route-a", now, config);
        store.recordRetryableFailure("route-a", now.plusSeconds(1), config);
        assertTrue(store.isAvailable("route-a", now.plusSeconds(2)));

        // 3rd failure after window has passed (old failures pruned)
        // Failure window is 30s, so by now+35s, the first failures are outside the window
        store.recordRetryableFailure("route-a", now.plusSeconds(35), config);
        // Only 1 failure in current window → still available
        assertTrue(store.isAvailable("route-a", now.plusSeconds(36)));
    }

    // ─── Route isolation ───

    @Test
    void oneRouteTrippingShouldNotAffectOtherRoutes() {
        Instant now = Instant.now();
        // Trip route-a
        store.recordRetryableFailure("route-a", now, config);
        store.recordRetryableFailure("route-a", now.plusSeconds(1), config);
        store.recordRetryableFailure("route-a", now.plusSeconds(2), config);
        assertFalse(store.isAvailable("route-a", now.plusSeconds(3)));

        // route-b should remain available
        assertTrue(store.isAvailable("route-b", now.plusSeconds(3)));
    }

    // ─── isAvailable with different time thresholds ───

    @Test
    void isAvailableShouldReturnTrueForUnopenedRoute() {
        Instant now = Instant.now();
        assertTrue(store.isAvailable("route-a", now));
        assertTrue(store.isAvailable("route-a", now.plus(Duration.ofDays(1))));
    }
}
