package io.gateway.oss.core.config;

import java.time.Duration;

public class ProviderHealthConfig {

    private boolean enabled = false;
    private Duration refreshInterval = Duration.ofMinutes(5);
    private boolean runOnStartup = true;
    private int disableAfterConsecutiveFailures = 3;
    private int recoverAfterConsecutiveSuccesses = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public boolean isRunOnStartup() {
        return runOnStartup;
    }

    public void setRunOnStartup(boolean runOnStartup) {
        this.runOnStartup = runOnStartup;
    }

    public int getDisableAfterConsecutiveFailures() {
        return disableAfterConsecutiveFailures;
    }

    public void setDisableAfterConsecutiveFailures(int disableAfterConsecutiveFailures) {
        this.disableAfterConsecutiveFailures = disableAfterConsecutiveFailures;
    }

    public int getRecoverAfterConsecutiveSuccesses() {
        return recoverAfterConsecutiveSuccesses;
    }

    public void setRecoverAfterConsecutiveSuccesses(int recoverAfterConsecutiveSuccesses) {
        this.recoverAfterConsecutiveSuccesses = recoverAfterConsecutiveSuccesses;
    }
}
