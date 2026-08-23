package io.gateway.oss.core.limit;

import io.gateway.oss.core.config.ConcurrentLimitConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentRequestLimiterTest {

    private GatewayProperties properties;
    private ConcurrentLimitConfig config;
    private ConcurrentRequestLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        config = properties.getConcurrentLimit();
        config.setEnabled(true);
        config.setMaxPerClient(3);
        config.setMaxGlobal(10);
        limiter = new ConcurrentRequestLimiter(properties);
    }

    @Test
    void acquire_underLimit_returnsPositivePermitNumber() {
        int result = limiter.acquire("client1");
        assertTrue(result > 0, "acquire should return a positive global in-flight count");
        assertEquals(1, limiter.getGlobalInFlight());
    }

    @Test
    void acquire_incrementsGlobalCounter() {
        limiter.acquire("client1");
        limiter.acquire("client2");
        assertEquals(2, limiter.getGlobalInFlight());
    }

    @Test
    void acquire_incrementsPerClientCounter() {
        limiter.acquire("client1");
        limiter.acquire("client1");
        assertEquals(2, limiter.getClientInFlight("client1"));
    }

    @Test
    void acquire_globalLimitExceeded_throwsException() {
        config.setMaxGlobal(2);
        limiter.acquire("client1");
        limiter.acquire("client2");

        GatewayException ex = assertThrows(GatewayException.class, () -> limiter.acquire("client3"));
        assertEquals(429, ex.getStatus().value());
        assertEquals("concurrent_limit_exceeded", ex.getCode());
        // Global counter should have been rolled back
        assertEquals(2, limiter.getGlobalInFlight());
    }

    @Test
    void acquire_perClientLimitExceeded_throwsException() {
        config.setMaxPerClient(2);
        limiter.acquire("client1");
        limiter.acquire("client1");

        GatewayException ex = assertThrows(GatewayException.class, () -> limiter.acquire("client1"));
        assertEquals(429, ex.getStatus().value());
        assertEquals("concurrent_limit_exceeded", ex.getCode());
        // Both per-client and global counters should have been rolled back
        assertEquals(2, limiter.getClientInFlight("client1"));
        assertEquals(2, limiter.getGlobalInFlight());
    }

    @Test
    void acquire_globalLimitCheckedBeforePerClient() {
        config.setMaxGlobal(1);
        config.setMaxPerClient(10);
        limiter.acquire("client1");

        // Global limit is 1, so even though per-client limit is 10, global should fail first
        GatewayException ex = assertThrows(GatewayException.class, () -> limiter.acquire("client2"));
        assertEquals("concurrent_limit_exceeded", ex.getCode());
        assertTrue(ex.getMessage().contains("Global"),
                "Exception message should mention 'Global' when global limit is exceeded");
    }

    @Test
    void release_decrementsInFlightCount() {
        limiter.acquire("client1");
        limiter.acquire("client1");
        assertEquals(2, limiter.getClientInFlight("client1"));
        assertEquals(2, limiter.getGlobalInFlight());

        limiter.release("client1");
        assertEquals(1, limiter.getClientInFlight("client1"));
        assertEquals(1, limiter.getGlobalInFlight());
    }

    @Test
    void release_removesClientCounterWhenReachesZero() {
        limiter.acquire("client1");
        assertEquals(1, limiter.getClientInFlight("client1"));

        limiter.release("client1");
        assertEquals(0, limiter.getClientInFlight("client1"));
    }

    @Test
    void acquire_disabled_returnsMinusOne() {
        config.setEnabled(false);
        int result = limiter.acquire("client1");
        assertEquals(-1, result);
        assertEquals(0, limiter.getGlobalInFlight());
    }

    @Test
    void release_disabled_isNoOp() {
        config.setEnabled(false);
        // Should not throw even when no permit was acquired
        assertDoesNotThrow(() -> limiter.release("client1"));
    }

    @Test
    void acquire_differentClientsAreIndependent() {
        limiter.acquire("client1");
        limiter.acquire("client1");
        assertEquals(2, limiter.getClientInFlight("client1"));

        // client2 has its own counter
        assertEquals(0, limiter.getClientInFlight("client2"));
        limiter.acquire("client2");
        assertEquals(1, limiter.getClientInFlight("client2"));
    }

    @Test
    void getGlobalInFlight_returnsZeroInitially() {
        assertEquals(0, limiter.getGlobalInFlight());
    }

    @Test
    void getClientInFlight_returnsZeroForUnknownClient() {
        assertEquals(0, limiter.getClientInFlight("unknown"));
    }
}