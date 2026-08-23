package io.gateway.oss.admin.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryClientCostStoreTest {

    private InMemoryClientCostStore store;
    private final Instant now = Instant.parse("2026-05-15T10:00:00Z");

    @BeforeEach
    void setUp() {
        store = new InMemoryClientCostStore();
    }

    @Test
    void shouldStartWithZeroCost() {
        assertEquals(BigDecimal.ZERO, store.currentDailyCost("client-1", now));
        assertEquals(BigDecimal.ZERO, store.currentMonthlyCost("client-1", now));
    }

    @Test
    void shouldAddAndReadDailyCost() {
        store.addDailyCost("client-1", new BigDecimal("1.500000"), now);
        store.addDailyCost("client-1", new BigDecimal("0.500000"), now);
        assertEquals(new BigDecimal("2.000000"), store.currentDailyCost("client-1", now));
    }

    @Test
    void shouldAddAndReadMonthlyCost() {
        store.addMonthlyCost("client-1", new BigDecimal("10.000000"), now);
        store.addMonthlyCost("client-1", new BigDecimal("5.000000"), now);
        assertEquals(new BigDecimal("15.000000"), store.currentMonthlyCost("client-1", now));
    }

    @Test
    void shouldIgnoreNullAndNonPositiveCost() {
        store.addDailyCost("client-1", BigDecimal.ZERO, now);
        store.addDailyCost("client-1", new BigDecimal("-1"), now);
        store.addDailyCost("client-1", null, now);
        assertEquals(BigDecimal.ZERO, store.currentDailyCost("client-1", now));
    }

    @Test
    void shouldCheckAndRecordDailyUnderBudget() {
        long result = store.checkAndRecord("client-1", 3000, 10000, now);
        assertEquals(3000L, result);
        assertEquals(new BigDecimal("0.003000"), store.currentDailyCost("client-1", now));
    }

    @Test
    void shouldRejectDailyOverBudget() {
        store.checkAndRecord("client-1", 8000, 10000, now);
        long result = store.checkAndRecord("client-1", 3000, 10000, now);
        assertEquals(-1L, result);
        assertEquals(new BigDecimal("0.008000"), store.currentDailyCost("client-1", now));
    }

    @Test
    void shouldCheckAndRecordMonthlyUnderBudget() {
        long result = store.checkAndRecordMonthly("client-1", 5000, 50000, now);
        assertEquals(5000L, result);
        assertEquals(new BigDecimal("0.005000"), store.currentMonthlyCost("client-1", now));
    }

    @Test
    void shouldRejectMonthlyOverBudget() {
        store.checkAndRecordMonthly("client-1", 45000, 50000, now);
        long result = store.checkAndRecordMonthly("client-1", 10000, 50000, now);
        assertEquals(-1L, result);
        assertEquals(new BigDecimal("0.045000"), store.currentMonthlyCost("client-1", now));
    }

    @Test
    void shouldReturnCurrentWhenZeroCostInCheckAndRecord() {
        store.addDailyCost("client-1", new BigDecimal("0.005000"), now);
        long result = store.checkAndRecord("client-1", 0, 10000, now);
        assertEquals(5000L, result); // returns micros

        long monthlyResult = store.checkAndRecordMonthly("client-1", 0, 50000, now);
        assertEquals(0L, monthlyResult);
    }

    @Test
    void shouldBucketByUtcDay() {
        Instant day1 = Instant.parse("2026-05-15T23:59:00Z");
        Instant day2 = Instant.parse("2026-05-16T00:01:00Z");

        store.addDailyCost("client-1", new BigDecimal("0.004000"), day1);
        store.addDailyCost("client-1", new BigDecimal("0.001000"), day2);

        assertEquals(new BigDecimal("0.004000"), store.currentDailyCost("client-1", day1));
        assertEquals(new BigDecimal("0.001000"), store.currentDailyCost("client-1", day2));
    }

    @Test
    void shouldBucketByUtcMonth() {
        Instant month1 = Instant.parse("2026-04-30T23:59:00Z");
        Instant month2 = Instant.parse("2026-05-01T00:01:00Z");

        store.addMonthlyCost("client-1", new BigDecimal("0.020000"), month1);
        store.addMonthlyCost("client-1", new BigDecimal("0.010000"), month2);

        assertEquals(new BigDecimal("0.020000"), store.currentMonthlyCost("client-1", month1));
        assertEquals(new BigDecimal("0.010000"), store.currentMonthlyCost("client-1", month2));
    }

    @Test
    void shouldResetForTests() {
        store.addDailyCost("client-1", new BigDecimal("1.00"), now);
        store.resetForTests();
        assertEquals(BigDecimal.ZERO, store.currentDailyCost("client-1", now));
    }
}
