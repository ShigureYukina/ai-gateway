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

    // ─── IP 维度（审查 F5：MAX_IP_ATTEMPTS=30，独立于用户名维度）───

    @Test
    void freshIp_hasNoFailures() {
        assertDoesNotThrow(() -> limiter.checkIp("203.0.113.5"));
    }

    @Test
    void blankOrNullIp_ignored() {
        assertDoesNotThrow(() -> limiter.checkIp(null));
        assertDoesNotThrow(() -> limiter.checkIp("  "));
        assertDoesNotThrow(() -> limiter.recordIpFailure(null));
        assertDoesNotThrow(() -> limiter.recordIpFailure(""));
        assertDoesNotThrow(() -> limiter.clearIp(null));
        // 未记录任何有效 IP，checkIp 不应抛出
        assertDoesNotThrow(() -> limiter.checkIp("203.0.113.9"));
    }

    @Test
    void belowIpThreshold_isAllowed() {
        for (int i = 0; i < 29; i++) {
            limiter.recordIpFailure("198.51.100.7");
        }
        // 29 次失败，未到 30 次阈值
        assertDoesNotThrow(() -> limiter.checkIp("198.51.100.7"));
    }

    @Test
    void afterMaxIpFailures_isBlocked() {
        for (int i = 0; i < 30; i++) {
            limiter.recordIpFailure("198.51.100.8");
        }

        GatewayException ex = assertThrows(GatewayException.class, () -> limiter.checkIp("198.51.100.8"));
        assertEquals(429, ex.getStatus().value());
        assertEquals("login_rate_limited_ip", ex.getCode());
    }

    @Test
    void clearIp_resetsCounter() {
        for (int i = 0; i < 30; i++) {
            limiter.recordIpFailure("198.51.100.9");
        }
        assertThrows(GatewayException.class, () -> limiter.checkIp("198.51.100.9"));

        limiter.clearIp("198.51.100.9");
        assertDoesNotThrow(() -> limiter.checkIp("198.51.100.9"));
    }

    @Test
    void ipAndUsernameDimensions_trackedIndependently() {
        // 用户名维度拉满不应影响 IP 维度
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("dave");
        }
        assertThrows(GatewayException.class, () -> limiter.check("dave"));
        assertDoesNotThrow(() -> limiter.checkIp("203.0.113.10"));

        // IP 维度拉满不应影响用户名维度
        for (int i = 0; i < 30; i++) {
            limiter.recordIpFailure("203.0.113.11");
        }
        assertThrows(GatewayException.class, () -> limiter.checkIp("203.0.113.11"));
        limiter.clear("dave");
        assertDoesNotThrow(() -> limiter.check("dave"));
    }
}
