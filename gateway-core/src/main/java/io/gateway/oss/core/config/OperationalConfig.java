package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.OperationalConfigView;

import java.util.ArrayList;
import java.util.List;

/**
 * System operational configuration — maintenance mode and emergency rate limit.
 */
public class OperationalConfig implements OperationalConfigView {

    private boolean maintenanceMode = false;
    private EmergencyRateLimit emergencyRateLimit = new EmergencyRateLimit();
    private List<String> maintenanceWhitelist = new ArrayList<>();

    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }

    public void setMaintenanceMode(boolean maintenanceMode) {
        this.maintenanceMode = maintenanceMode;
    }

    public EmergencyRateLimit getEmergencyRateLimit() {
        return emergencyRateLimit;
    }

    public void setEmergencyRateLimit(EmergencyRateLimit emergencyRateLimit) {
        this.emergencyRateLimit = emergencyRateLimit;
    }

    public List<String> getMaintenanceWhitelist() {
        return maintenanceWhitelist;
    }

    public void setMaintenanceWhitelist(List<String> maintenanceWhitelist) {
        this.maintenanceWhitelist = maintenanceWhitelist != null ? maintenanceWhitelist : new ArrayList<>();
    }

    public static class EmergencyRateLimit implements OperationalConfigView.EmergencyRateLimitView {
        private boolean enabled = false;
        private int maxRequestsPerMinute = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns the effective max requests per minute, clamped to at least 1.
         * Use {@link #getRawMaxRequestsPerMinute()} for the unclamped config value.
         */
        public int getMaxRequestsPerMinute() {
            return Math.max(1, maxRequestsPerMinute);
        }

        public int getRawMaxRequestsPerMinute() {
            return maxRequestsPerMinute;
        }

        public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
            this.maxRequestsPerMinute = maxRequestsPerMinute;
        }
    }
}
