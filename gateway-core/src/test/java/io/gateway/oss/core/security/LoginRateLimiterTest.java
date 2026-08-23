package io.gateway.oss.core.security;

import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {

    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new LoginRateLimiter();
    }

    @Test
    void freshUsername_hasNoFailures() {
        // A fresh username should not throw on check
        assertDoesNotThrow(() -> limiter.check("newuser"));
    }

    @Test
    void recordFailure_incrementsCount() {
        // After recording a failure, check should still pass (under max)
        limiter.recordFailure("alice");
        assertDoesNotThrow(() -> limiter.check("alice"));

        // Record multiple failures, still under max
        for (int i = 1; i < 9; i++) {
            limiter.recordFailure("alice");
        }
        // 9 failures total, still under MAX_ATTEMPTS (10)
        assertDoesNotThrow(() -> limiter.check("alice"));
    }

    @Test
    void afterMaxFailures_isBlocked() {
        // Record MAX_ATTEMPTS failures
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("bob");
        }

        GatewayException ex = assertThrows(GatewayException.class, () -> limiter.check("bob"));
        assertEquals(429, ex.getStatus().value());
        assertEquals("login_rate_limited", ex.getCode());
        assertTrue(ex.getMessage().contains("Too many login attempts"));
    }

    @Test
    void clear_resetsCounter() {
        // Block the user first
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("charlie");
        }
        assertThrows(GatewayException.class, () -> limiter.check("charlie"));

        // Clear should reset
        limiter.clear("charlie");
        assertDoesNotThrow(() -> limiter.check("charlie"));
    }

    @Test
    void multipleUsers_trackedIndependently() {
        // Block user1
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("user1");
        }
        assertThrows(GatewayException.class, () -> limiter.check("user1"));

        // user2 should still be unaffected
        assertDoesNotThrow(() -> limiter.check("user2"));

        // Record a few failures for user2 — should still pass
        limiter.recordFailure("user2");
        limiter.recordFailure("user2");
        assertDoesNotThrow(() -> limiter.check("user2"));

        // Clear user1, user2 still has 2 failures
        limiter.clear("user1");
        assertDoesNotThrow(() -> limiter.check("user1"));
    }
}
