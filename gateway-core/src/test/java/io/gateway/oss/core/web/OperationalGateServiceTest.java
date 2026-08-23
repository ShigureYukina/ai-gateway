package io.gateway.oss.core.web;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.OperationalConfig;
import io.gateway.oss.core.error.GatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OperationalGateServiceTest {

    private GatewayProperties properties;
    private OperationalGateService service;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.setOperational(new OperationalConfig());
        service = new OperationalGateService(properties);
    }

    @Test
    void maintenanceModeDisabled_shouldPass() {
        assertDoesNotThrow(() -> service.preCheck());
    }

    @Test
    void maintenanceModeEnabled_shouldThrow503() {
        properties.getOperational().setMaintenanceMode(true);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.preCheck());
        assertEquals(503, ex.getStatus().value());
        assertEquals("maintenance_mode", ex.getCode());
    }

    @Test
    void maintenanceModeEnabled_withWhitelistedToken_shouldPass() {
        properties.getOperational().setMaintenanceMode(true);
        properties.getOperational().setMaintenanceWhitelist(List.of("secret-token-123"));
        assertDoesNotThrow(() -> service.preCheck("secret-token-123"));
    }

    @Test
    void maintenanceModeEnabled_withNonWhitelistedToken_shouldThrow() {
        properties.getOperational().setMaintenanceMode(true);
        properties.getOperational().setMaintenanceWhitelist(List.of("secret-token-123"));
        assertThrows(GatewayException.class, () -> service.preCheck("wrong-token"));
    }

    @Test
    void maintenanceModeToggle_shouldImmediatelyTakeEffect() {
        properties.getOperational().setMaintenanceMode(true);
        assertThrows(GatewayException.class, () -> service.preCheck());
        properties.getOperational().setMaintenanceMode(false);
        assertDoesNotThrow(() -> service.preCheck());
    }

    @Test
    void emergencyRateLimitDisabled_shouldPass() {
        properties.getOperational().getEmergencyRateLimit().setEnabled(false);
        for (int i = 0; i < 100; i++) {
            assertDoesNotThrow(() -> service.preCheck());
        }
    }

    @Test
    void emergencyRateLimitEnabled_shouldAllowUpToMax() {
        OperationalConfig.EmergencyRateLimit erl = new OperationalConfig.EmergencyRateLimit();
        erl.setEnabled(true);
        erl.setMaxRequestsPerMinute(5);
        properties.getOperational().setEmergencyRateLimit(erl);

        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> service.preCheck(), "Request " + (i + 1) + " should pass");
        }
    }

    @Test
    void emergencyRateLimitEnabled_shouldRejectAfterMax() {
        OperationalConfig.EmergencyRateLimit erl = new OperationalConfig.EmergencyRateLimit();
        erl.setEnabled(true);
        erl.setMaxRequestsPerMinute(3);
        properties.getOperational().setEmergencyRateLimit(erl);

        assertDoesNotThrow(() -> service.preCheck());
        assertDoesNotThrow(() -> service.preCheck());
        assertDoesNotThrow(() -> service.preCheck());

        GatewayException ex = assertThrows(GatewayException.class, () -> service.preCheck());
        assertEquals(429, ex.getStatus().value());
        assertEquals("emergency_rate_limited", ex.getCode());
    }

    @Test
    void emergencyRateLimit_rejectedRequestsDoNotCountAgainstLimit() {
        OperationalConfig.EmergencyRateLimit erl = new OperationalConfig.EmergencyRateLimit();
        erl.setEnabled(true);
        erl.setMaxRequestsPerMinute(2);
        properties.getOperational().setEmergencyRateLimit(erl);

        // 2 allowed
        assertDoesNotThrow(() -> service.preCheck());
        assertDoesNotThrow(() -> service.preCheck());
        // 3rd rejected
        assertThrows(GatewayException.class, () -> service.preCheck());

        // Snapshot should show 2, not 3
        var snap = service.snapshot();
        assertEquals(2, snap.emergencyWindowCount());
    }

    @Test
    void emergencyRateLimit_maxRequestsPerMinute_clampedToAtLeast1() {
        OperationalConfig.EmergencyRateLimit erl = new OperationalConfig.EmergencyRateLimit();
        erl.setEnabled(true);
        erl.setMaxRequestsPerMinute(0);
        properties.getOperational().setEmergencyRateLimit(erl);

        // Should allow exactly 1 (clamped from 0)
        assertDoesNotThrow(() -> service.preCheck());
        assertThrows(GatewayException.class, () -> service.preCheck());
    }

    @Test
    void emergencyRateLimit_negativeMax_clampedTo1() {
        OperationalConfig.EmergencyRateLimit erl = new OperationalConfig.EmergencyRateLimit();
        erl.setEnabled(true);
        erl.setMaxRequestsPerMinute(-5);
        properties.getOperational().setEmergencyRateLimit(erl);

        assertDoesNotThrow(() -> service.preCheck());
        assertThrows(GatewayException.class, () -> service.preCheck());
    }

    @Test
    void snapshot_reportsRawConfigValue() {
        OperationalConfig.EmergencyRateLimit erl = new OperationalConfig.EmergencyRateLimit();
        erl.setEnabled(true);
        erl.setMaxRequestsPerMinute(0);
        properties.getOperational().setEmergencyRateLimit(erl);

        var snap = service.snapshot();
        assertEquals(0, snap.emergencyMaxRequestsPerMinute()); // raw, not clamped
    }

    @Test
    void snapshot_reportsCurrentWindowState() {
        OperationalConfig.EmergencyRateLimit erl = new OperationalConfig.EmergencyRateLimit();
        erl.setEnabled(true);
        erl.setMaxRequestsPerMinute(100);
        properties.getOperational().setEmergencyRateLimit(erl);

        service.preCheck();
        service.preCheck();

        var snap = service.snapshot();
        assertTrue(snap.emergencyRateLimitEnabled());
        assertEquals(2, snap.emergencyWindowCount());
    }

    @Test
    void snapshot_defaultState_noEmergencyOrMaintenance() {
        var snap = service.snapshot();
        assertFalse(snap.maintenanceMode());
        assertFalse(snap.emergencyRateLimitEnabled());
        assertEquals(60, snap.emergencyMaxRequestsPerMinute()); // default value
    }

    @Test
    void concurrentSmokeTest_shouldEnforceExactLimit() throws InterruptedException {
        OperationalConfig.EmergencyRateLimit erl = new OperationalConfig.EmergencyRateLimit();
        erl.setEnabled(true);
        erl.setMaxRequestsPerMinute(20);
        properties.getOperational().setEmergencyRateLimit(erl);

        int totalThreads = 40;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < totalThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    try {
                        service.preCheck();
                        allowed.incrementAndGet();
                    } catch (GatewayException e) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        assertEquals(20, allowed.get(), "Exactly 20 requests should be allowed");
        assertEquals(20, rejected.get(), "Exactly 20 requests should be rejected");
    }
}
