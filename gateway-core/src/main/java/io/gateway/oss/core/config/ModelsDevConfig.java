package io.gateway.oss.core.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.time.Duration;

public class ModelsDevConfig {

    private boolean enabled = false;
    @NotBlank
    private String endpoint = "https://models.dev/api.json";
    private Duration refreshInterval = Duration.ofMinutes(30);
    private Duration timeout = Duration.ofSeconds(5);
    private boolean runOnStartup = true;
    private boolean preferRemotePricing = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isRunOnStartup() {
        return runOnStartup;
    }

    public void setRunOnStartup(boolean runOnStartup) {
        this.runOnStartup = runOnStartup;
    }

    public boolean isPreferRemotePricing() {
        return preferRemotePricing;
    }

    public void setPreferRemotePricing(boolean preferRemotePricing) {
        this.preferRemotePricing = preferRemotePricing;
    }

    @AssertTrue(message = "refresh-interval must be greater than 0")
    public boolean isRefreshIntervalValid() {
        return refreshInterval != null && !refreshInterval.isZero() && !refreshInterval.isNegative();
    }

    @AssertTrue(message = "timeout must be greater than 0")
    public boolean isTimeoutValid() {
        return timeout != null && !timeout.isZero() && !timeout.isNegative();
    }
}
