package io.gateway.oss.core.limit;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.LimitConfig;
import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRateLimiterTest {

    private InMemoryRateLimiter limiter;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        LimitConfig limit = new LimitConfig();
        limit.setRequestsPerWindow(3);
        limit.setWindow(Duration.ofMinutes(1));
        properties.setLimit(limit);
        limiter = new InMemoryRateLimiter(properties);
    }

    @Test
    void check_withinLimit_shouldPass() {
        assertDoesNotThrow(() -> limiter.check("client1"));
        assertDoesNotThrow(() -> limiter.check("client1"));
        assertDoesNotThrow(() -> limiter.check("client1"));
    }

    @Test
    void check_exceedLimit_shouldThrow429() {
        limiter.check("client1");
        limiter.check("client1");
        limiter.check("client1");

        GatewayException ex = assertThrows(GatewayException.class, () -> limiter.check("client1"));
        assertEquals(429, ex.getStatus().value());
        assertEquals("rate_limited", ex.getCode());
    }

    @Test
    void check_differentClients_independent() {
        limiter.check("client1");
        limiter.check("client1");
        limiter.check("client1");

        assertDoesNotThrow(() -> limiter.check("client2"));
        assertDoesNotThrow(() -> limiter.check("client2"));
        assertDoesNotThrow(() -> limiter.check("client2"));
    }

    @Test
    void reset_shouldClearAllBuckets() {
        limiter.check("client1");
        limiter.check("client1");
        limiter.check("client1");

        limiter.reset();

        assertDoesNotThrow(() -> limiter.check("client1"));
    }
}
