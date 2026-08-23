package io.gateway.oss.admin.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryClientUsageStoreTest {

    private InMemoryClientUsageStore store;
    private final Instant now = Instant.parse("2026-05-15T10:00:00Z");

    @BeforeEach
    void setUp() {
        store = new InMemoryClientUsageStore();
    }

    @Test
    void shouldStartWithZeroUsage() {
        assertEquals(0L, store.currentDailyUsage("client-1", now));
        assertEquals(0L, store.currentMonthlyUsage("client-1", now));
        assertEquals(0L, store.currentDailyRequestCount("client-1", now));
    }

    @Test
    void shouldAddAndReadDailyUsage() {
        store.addDailyUsage("client-1", 50, now);
        store.addDailyUsage("client-1", 30, now);
        assertEquals(80L, store.currentDailyUsage("client-1", now));
    }

    @Test
    void shouldAddAndReadMonthlyUsage() {
        store.addMonthlyUsage("client-1", 200, now);
        store.addMonthlyUsage("client-1", 150, now);
        assertEquals(350L, store.currentMonthlyUsage("client-1", now));
    }

    @Test
    void shouldAddAndReadDailyRequestCount() {
        store.addDailyRequestCount("client-1", now);
        store.addDailyRequestCount("client-1", now);
        store.addDailyRequestCount("client-1", now);
        assertEquals(3L, store.currentDailyRequestCount("client-1", now));
    }

    @Test
    void shouldIgnoreNonPositiveTokens() {
        store.addDailyUsage("client-1", 0, now);
        store.addDailyUsage("client-1", -5, now);
        assertEquals(0L, store.currentDailyUsage("client-1", now));

        store.addMonthlyUsage("client-1", 0, now);
        assertEquals(0L, store.currentMonthlyUsage("client-1", now));
    }

    @Test
    void shouldCheckAndRecordDailyUnderQuota() {
        long result = store.checkAndRecord("client-1", 30, 100, now);
        assertEquals(30L, result);
        assertEquals(30L, store.currentDailyUsage("client-1", now));
        assertEquals(1L, store.currentDailyRequestCount("client-1", now));
    }

    @Test
    void shouldRejectDailyOverQuota() {
        store.checkAndRecord("client-1", 80, 100, now);
        long result = store.checkAndRecord("client-1", 30, 100, now);
        assertEquals(-1L, result);
        assertEquals(80L, store.currentDailyUsage("client-1", now));
    }

    @Test
    void shouldCheckAndRecordMonthlyUnderQuota() {
        long result = store.checkAndRecordMonthly("client-1", 300, 1000, now);
        assertEquals(300L, result);
        assertEquals(300L, store.currentMonthlyUsage("client-1", now));
    }

    @Test
    void shouldRejectMonthlyOverQuota() {
        store.checkAndRecordMonthly("client-1", 800, 1000, now);
        long result = store.checkAndRecordMonthly("client-1", 300, 1000, now);
        assertEquals(-1L, result);
        assertEquals(800L, store.currentMonthlyUsage("client-1", now));
    }

    @Test
    void shouldHandleZeroTokensCheckAndRecord() {
        store.addDailyUsage("client-1", 50, now);
        long result = store.checkAndRecord("client-1", 0, 100, now);
        assertEquals(50L, result); // returns current without recording

        long monthlyResult = store.checkAndRecordMonthly("client-1", 0, 1000, now);
        assertEquals(0L, monthlyResult);
    }

    @Test
    void shouldBucketByUtcDay() {
        Instant day1 = Instant.parse("2026-05-15T23:59:00Z");
        Instant day2 = Instant.parse("2026-05-16T00:01:00Z");

        store.addDailyUsage("client-1", 40, day1);
        store.addDailyUsage("client-1", 10, day2);

        assertEquals(40L, store.currentDailyUsage("client-1", day1));
        assertEquals(10L, store.currentDailyUsage("client-1", day2));
    }

    @Test
    void shouldBucketByUtcMonth() {
        Instant month1 = Instant.parse("2026-04-30T23:59:00Z");  // still April in UTC
        Instant month2 = Instant.parse("2026-05-01T00:01:00Z");  // May

        store.addMonthlyUsage("client-1", 100, month1);
        store.addMonthlyUsage("client-1", 200, month2);

        assertEquals(100L, store.currentMonthlyUsage("client-1", month1));
        assertEquals(200L, store.currentMonthlyUsage("client-1", month2));
    }

    @Test
    void shouldResetForTests() {
        store.addDailyUsage("client-1", 50, now);
        store.resetForTests();
        assertEquals(0L, store.currentDailyUsage("client-1", now));
    }
}
