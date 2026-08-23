package io.gateway.oss.core.util;

import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateParamParserTest {

    @Test
    void resolveIsoDateOrTodayReturnsTodayForNull() {
        assertEquals(LocalDate.now(ZoneOffset.UTC), DateParamParser.resolveIsoDateOrToday(null, "day"));
    }

    @Test
    void resolveIsoDateOrTodayParsesIsoDate() {
        assertEquals(LocalDate.of(2026, 6, 4), DateParamParser.resolveIsoDateOrToday("2026-06-04", "day"));
    }

    @Test
    void resolveIsoDateOrTodayThrowsGatewayExceptionForInvalidDate() {
        assertThrows(GatewayException.class, () -> DateParamParser.resolveIsoDateOrToday("invalid", "day"));
    }

    @Test
    void resolveIsoMonthOrCurrentReturnsCurrentMonthForNull() {
        assertEquals(YearMonth.now(ZoneOffset.UTC), DateParamParser.resolveIsoMonthOrCurrent(null, "month"));
    }

    @Test
    void resolveIsoMonthOrCurrentParsesIsoMonth() {
        assertEquals(YearMonth.of(2026, 6), DateParamParser.resolveIsoMonthOrCurrent("2026-06", "month"));
    }

    @Test
    void resolveIsoMonthOrCurrentThrowsGatewayExceptionForInvalidMonth() {
        assertThrows(GatewayException.class, () -> DateParamParser.resolveIsoMonthOrCurrent("invalid", "month"));
    }
}
