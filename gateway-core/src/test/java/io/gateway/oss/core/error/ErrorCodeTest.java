package io.gateway.oss.core.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class ErrorCodeTest {

    @Test
    void fromCode_knownCode_shouldReturnEnum() {
        assertEquals(ErrorCode.UNAUTHORIZED, ErrorCode.fromCode("unauthorized"));
    }

    @Test
    void fromCode_unknownCode_shouldReturnNull() {
        assertNull(ErrorCode.fromCode("nonexistent_code"));
    }

    @Test
    void status_shouldReturnCorrectHttpStatus() {
        assertEquals(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.status());
    }

    @Test
    void code_shouldReturnLowerCaseString() {
        assertEquals("rate_limited", ErrorCode.RATE_LIMITED.code());
    }

    @Test
    void exception_shouldCreateGatewayException() {
        GatewayException ex = ErrorCode.FORBIDDEN.exception();
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("forbidden", ex.getCode());
    }

    @Test
    void exception_withMessage_shouldUseProvidedMessage() {
        GatewayException ex = ErrorCode.NOT_FOUND.exception("custom");
        assertEquals("custom", ex.getMessage());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void fromCode_circuitBreakerOpen_shouldResolve() {
        assertEquals(ErrorCode.CIRCUIT_BREAKER_OPEN, ErrorCode.fromCode("circuit_breaker_open"));
    }

    @Test
    void circuitBreakerOpen_shouldReturnServiceUnavailable() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.CIRCUIT_BREAKER_OPEN.status());
        assertEquals("circuit_breaker_open", ErrorCode.CIRCUIT_BREAKER_OPEN.code());
    }
}
