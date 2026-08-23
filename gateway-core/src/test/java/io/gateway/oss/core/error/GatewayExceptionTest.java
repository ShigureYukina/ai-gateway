package io.gateway.oss.core.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class GatewayExceptionTest {

    @Test
    void shouldCreateWithStatusCodeAndMessage() {
        GatewayException ex = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "upstream failed");
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("upstream_error", ex.getCode());
        assertEquals("upstream failed", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void shouldCreateWithCause() {
        RuntimeException cause = new RuntimeException("root cause");
        GatewayException ex = new GatewayException(
                HttpStatus.SERVICE_UNAVAILABLE, "circuit_breaker_open",
                "Circuit breaker open", cause);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("circuit_breaker_open", ex.getCode());
        assertEquals("Circuit breaker open", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void causeChainShouldBeComplete() {
        IllegalStateException inner = new IllegalStateException("inner");
        IllegalArgumentException middle = new IllegalArgumentException("middle", inner);
        GatewayException ex = new GatewayException(
                HttpStatus.BAD_GATEWAY, "upstream_error",
                "outer", middle);

        assertSame(middle, ex.getCause());
        assertSame(inner, ex.getCause().getCause());
        assertNull(ex.getCause().getCause().getCause());
    }
}
