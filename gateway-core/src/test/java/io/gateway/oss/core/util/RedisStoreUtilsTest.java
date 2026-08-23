package io.gateway.oss.core.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisStoreUtilsTest {

    @Test
    void safePrefixReturnsGatewayForNull() {
        assertEquals("gateway", RedisStoreUtils.safePrefix(null));
    }

    @Test
    void safePrefixReturnsGatewayForEmptyString() {
        assertEquals("gateway", RedisStoreUtils.safePrefix(""));
    }

    @Test
    void safePrefixReturnsTrimmedCustomPrefix() {
        assertEquals("my-prefix", RedisStoreUtils.safePrefix("my-prefix"));
    }

    @Test
    void dayKeyReturnsExpectedUtcFormat() {
        Instant now = Instant.parse("2026-06-04T12:34:56Z");

        assertEquals("client-a:2026-06-04", RedisStoreUtils.dayKey("client-a", now));
    }

    @Test
    void monthKeyReturnsExpectedUtcFormat() {
        Instant now = Instant.parse("2026-06-04T12:34:56Z");

        assertEquals("client-a:2026-06-01", RedisStoreUtils.monthKey("client-a", now));
    }

    @Test
    void ttlToNextUtcDayReturnsPositiveReasonableDuration() {
        Instant now = Instant.now();

        Duration ttl = RedisStoreUtils.ttlToNextUtcDay(now);

        assertNotNull(ttl);
        assertTrue(ttl.getSeconds() > 0);
        assertTrue(ttl.compareTo(Duration.ofDays(1)) < 0);
    }

    @Test
    void ttlToNextUtcMonthReturnsPositiveReasonableDuration() {
        Instant now = Instant.now();

        Duration ttl = RedisStoreUtils.ttlToNextUtcMonth(now);

        assertNotNull(ttl);
        assertTrue(ttl.getSeconds() > 0);
        assertTrue(ttl.compareTo(Duration.ofDays(32)) < 0);
    }

    @Test
    void normalizedReturnsNullForNull() {
        assertNull(RedisStoreUtils.normalized(null));
    }

    @Test
    void normalizedReturnsNullForBlank() {
        assertNull(RedisStoreUtils.normalized("  "));
    }

    @Test
    void normalizedReturnsTrimmedValue() {
        assertEquals("hello", RedisStoreUtils.normalized("hello  "));
    }

    @Test
    void resolveDayReturnsTodayForNull() {
        LocalDate expected = LocalDate.now(ZoneOffset.UTC);

        assertEquals(expected, RedisStoreUtils.resolveDay(null));
    }

    @Test
    void resolveDayParsesIsoDate() {
        assertEquals(LocalDate.of(2026, 6, 4), RedisStoreUtils.resolveDay("2026-06-04"));
    }
}
