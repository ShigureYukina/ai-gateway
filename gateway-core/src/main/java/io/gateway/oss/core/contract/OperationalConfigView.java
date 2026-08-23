package io.gateway.oss.core.contract;

import java.util.List;

public interface OperationalConfigView {
    boolean isMaintenanceMode();
    EmergencyRateLimitView getEmergencyRateLimit();
    List<String> getMaintenanceWhitelist();

    interface EmergencyRateLimitView {
        boolean isEnabled();
        int getMaxRequestsPerMinute();
        int getRawMaxRequestsPerMinute();
    }
}
