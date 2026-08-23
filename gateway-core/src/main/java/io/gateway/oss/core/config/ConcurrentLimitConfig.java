package io.gateway.oss.core.config;

import jakarta.validation.constraints.Min;

/**
 * Configuration for concurrent (in-flight) request limiting per client.
 */
public class ConcurrentLimitConfig {

    private boolean enabled = false;

    @Min(1)
    private int maxPerClient = 10;

    @Min(1)
    private int maxGlobal = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPerClient() {
        return maxPerClient;
    }

    public void setMaxPerClient(int maxPerClient) {
        this.maxPerClient = maxPerClient;
    }

    public int getMaxGlobal() {
        return maxGlobal;
    }

    public void setMaxGlobal(int maxGlobal) {
        this.maxGlobal = maxGlobal;
    }
}
