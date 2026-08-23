package io.gateway.oss.core.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Shared utility methods extracted from Redis store implementations,
 * in-memory stores, and controllers to eliminate duplication.
 */
public final class RedisStoreUtils {

    /** Scale used for cost micros conversion (6 decimal places for USD). */
    public static final int COST_SCALE = 6;

    private RedisStoreUtils() {
        // utility class
    }

    /**
     * Returns a safe key prefix: "gateway" if the value is null or blank,
     * otherwise the trimmed value.
     */
    public static String safePrefix(String value) {
        if (value == null || value.isBlank()) {
            return "gateway";
        }
        return value.trim();
    }

    /**
     * Builds a day-level key: {@code clientId + ":" + date} where date is
     * formatted as {@code yyyy-MM-dd} in UTC.
     */
    public static String dayKey(String clientId, Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        return clientId + ":" + day;
    }

    /**
     * Builds a month-level key: {@code clientId + ":" + month} where month is
     * the first day of the month formatted as {@code yyyy-MM-01} in UTC.
     */
    public static String monthKey(String clientId, Instant now) {
        LocalDate month = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return clientId + ":" + month;
    }

    /**
     * Computes the Duration from {@code now} until the next UTC midnight.
     * Returns at least 1 second if the computed duration is zero or negative.
     */
    public static Duration ttlToNextUtcDay(Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneOffset.UTC);
        ZonedDateTime next = zdt.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        Duration ttl = Duration.between(zdt, next);
        if (ttl.isNegative() || ttl.isZero()) {
            return Duration.ofSeconds(1);
        }
        return ttl;
    }

    /**
     * Computes the Duration from {@code now} until the start of the next UTC month.
     * Returns at least 1 second if the computed duration is zero or negative.
     */
    public static Duration ttlToNextUtcMonth(Instant now) {
        ZonedDateTime zdt = now.atZone(ZoneOffset.UTC);
        ZonedDateTime next = zdt.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC);
        Duration ttl = Duration.between(zdt, next);
        if (ttl.isNegative() || ttl.isZero()) {
            return Duration.ofSeconds(1);
        }
        return ttl;
    }

    /**
     * Normalizes a string parameter: trims whitespace and returns null for blank input.
     * Equivalent to {@code StringUtils.blankToNull(value)} followed by trim.
     */
    public static String normalized(String value) {
        String trimmed = StringUtils.blankToNull(value);
        return trimmed == null ? null : trimmed.trim();
    }

    /**
     * Resolves a day parameter string to a {@link LocalDate}.
     * If the value is null or blank, defaults to today (UTC).
     *
     * @param day the ISO date string (yyyy-MM-dd), may be null/blank
     * @return the resolved date
     */
    public static LocalDate resolveDay(String day) {
        return DateParamParser.resolveIsoDateOrToday(day, "day");
    }
}
