package io.gateway.oss.admin.limit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryClientTpmStoreTest {

    private final Instant now = Instant.parse("2026-06-04T10:15:30Z");

    private InMemoryClientTpmStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryClientTpmStore();
    }

    @Test
    void reserve_deductsTokensFromLimit() {
        long result = store.reserve("client-1", 30, 100, now);

        assertEquals(30L, result);
        assertEquals(30L, store.currentMinuteUsage("client-1", now));
    }

    @Test
    void reserve_returnsNegativeOneWhenLimitExceeded() {
        store.reserve("client-1", 80, 100, now);

        long result = store.reserve("client-1", 25, 100, now);

        assertEquals(-1L, result);
        assertEquals(80L, store.currentMinuteUsage("client-1", now));
    }

    @Test
    void reserve_withZeroTokensReturnsCurrentUsage() {
        store.reserve("client-1", 40, 100, now);

        long result = store.reserve("client-1", 0, 100, now);

        assertEquals(40L, result);
    }

    @Test
    void currentMinuteUsage_returnsZeroForUnknownClient() {
        assertEquals(0L, store.currentMinuteUsage("unknown-client", now));
    }

    @Test
    void currentMinuteUsage_returnsAccumulatedUsage() {
        store.reserve("client-1", 25, 100, now);
        store.reserve("client-1", 15, 100, now);

        assertEquals(40L, store.currentMinuteUsage("client-1", now));
    }

    @Test
    void adjust_addsDeltaTokens() {
        store.reserve("client-1", 20, 100, now);

        store.adjust("client-1", 15, now);

        assertEquals(35L, store.currentMinuteUsage("client-1", now));
    }

    @Test
    void adjust_subtractsDeltaTokensWithoutGoingBelowZero() {
        store.reserve("client-1", 20, 100, now);

        store.adjust("client-1", -50, now);

        assertEquals(0L, store.currentMinuteUsage("client-1", now));
    }

    @Test
    void adjust_withNoOpDeltaDoesNothing() {
        store.reserve("client-1", 20, 100, now);

        store.adjust("client-1", 0, now);

        assertEquals(20L, store.currentMinuteUsage("client-1", now));
    }

    @Test
    void differentClientsHaveIndependentCounters() {
        store.reserve("client-1", 10, 100, now);
        store.reserve("client-2", 25, 100, now);

        assertEquals(10L, store.currentMinuteUsage("client-1", now));
        assertEquals(25L, store.currentMinuteUsage("client-2", now));
    }

    @Test
    void resetForTests_clearsAllCounters() {
        store.reserve("client-1", 10, 100, now);
        store.reserve("client-2", 25, 100, now);

        store.resetForTests();

        assertEquals(0L, store.currentMinuteUsage("client-1", now));
        assertEquals(0L, store.currentMinuteUsage("client-2", now));
    }
}
