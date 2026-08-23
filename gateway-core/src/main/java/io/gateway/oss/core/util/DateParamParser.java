package io.gateway.oss.core.util;

import io.gateway.oss.core.error.GatewayException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class DateParamParser {

    private DateParamParser() {
    }

    public static LocalDate resolveIsoDateOrToday(String value, String fieldName) {
        String trimmed = StringUtils.blankToNull(value);
        if (trimmed == null) {
            return LocalDate.now(ZoneOffset.UTC);
        }
        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw invalid(fieldName, "YYYY-MM-DD");
        }
    }

    public static YearMonth resolveIsoMonthOrCurrent(String value, String fieldName) {
        String trimmed = StringUtils.blankToNull(value);
        if (trimmed == null) {
            return YearMonth.now(ZoneOffset.UTC);
        }
        try {
            return YearMonth.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (DateTimeParseException e) {
            throw invalid(fieldName, "YYYY-MM");
        }
    }

    private static GatewayException invalid(String fieldName, String expectedFormat) {
        return new GatewayException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Invalid " + fieldName + ", expected " + expectedFormat
        );
    }
}
