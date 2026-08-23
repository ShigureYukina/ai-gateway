package io.gateway.oss.core.web;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.OperationalConfig;
import io.gateway.oss.core.error.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-request gate for maintenance mode and emergency rate limiting.
 * <p>
 * Lock-free: uses a single packed AtomicLong for window state.
 * High 32 bits = epoch-minute, low 32 bits = count.
 */
@Service
public class OperationalGateService {

    private final GatewayProperties properties;
    private final AtomicLong packedState = new AtomicLong(0L);

    public OperationalGateService(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * Pre-check with optional bearer token for maintenance whitelist.
     */
    public void preCheck(String bearerToken) {
        checkMaintenanceMode(bearerToken);
        checkEmergencyRateLimit();
    }

    /**
     * Pre-check without whitelist bypass.
     */
    public void preCheck() {
        preCheck(null);
    }

    private void checkMaintenanceMode(String bearerToken) {
        OperationalConfig config = properties.getOperational();
        if (config == null || !config.isMaintenanceMode()) {
            return;
        }
        // Check whitelist
        if (bearerToken != null && !bearerToken.isBlank()) {
            List<String> whitelist = config.getMaintenanceWhitelist();
            if (whitelist != null && whitelist.contains(bearerToken)) {
                return;
            }
        }
        throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "maintenance_mode", "System is under maintenance");
    }

    private void checkEmergencyRateLimit() {
        OperationalConfig config = properties.getOperational();
        if (config == null) {
            return;
        }
        OperationalConfig.EmergencyRateLimit erl = config.getEmergencyRateLimit();
        if (erl == null || !erl.isEnabled()) {
            return;
        }

        int maxPerMinute = erl.getMaxRequestsPerMinute();
        long currentMinute = java.time.Instant.now().getEpochSecond() / 60;

        while (true) {
            long current = packedState.get();
            long windowMinute = (int) (current >> 32);
            int count = (int) (current & 0xFFFFFFFFL);

            if (windowMinute != currentMinute) {
                // New window — reset count to 1
                long newState = ((long) currentMinute << 32) | 1L;
                if (packedState.compareAndSet(current, newState)) {
                    return; // first request in new window, always allowed
                }
                continue; // CAS failed, retry
            }

            // Same window
            if (count >= maxPerMinute) {
                throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "emergency_rate_limited",
                        "Emergency rate limit exceeded (" + maxPerMinute + " req/min)");
            }

            long newState = ((long) windowMinute << 32) | ((long) (count + 1));
            if (packedState.compareAndSet(current, newState)) {
                return; // allowed
            }
            // CAS failed, retry
        }
    }

    /**
     * Snapshot of current operational state for read-only status endpoints.
     */
    public OperationalGateState snapshot() {
        OperationalConfig config = properties.getOperational();
        boolean maintenance = config != null && config.isMaintenanceMode();
        boolean emergencyEnabled = config != null && config.getEmergencyRateLimit() != null && config.getEmergencyRateLimit().isEnabled();
        int emergencyMax = config != null && config.getEmergencyRateLimit() != null ? config.getEmergencyRateLimit().getRawMaxRequestsPerMinute() : 0;

        long current = packedState.get();
        long windowMinute = (int) (current >> 32);
        int count = (int) (current & 0xFFFFFFFFL);

        return new OperationalGateState(maintenance, emergencyEnabled, emergencyMax, count, windowMinute);
    }

    public record OperationalGateState(
            boolean maintenanceMode,
            boolean emergencyRateLimitEnabled,
            int emergencyMaxRequestsPerMinute,
            int emergencyWindowCount,
            long emergencyWindowMinute
    ) {}
}
