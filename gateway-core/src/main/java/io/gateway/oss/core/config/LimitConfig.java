package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.LimitConfigView;
import jakarta.validation.constraints.Min;

import java.time.Duration;

public class LimitConfig implements LimitConfigView {

    @Min(1)
    private int requestsPerWindow = 60;
    private Duration window = Duration.ofMinutes(1);

    public int getRequestsPerWindow() {
        return requestsPerWindow;
    }

    public void setRequestsPerWindow(int requestsPerWindow) {
        this.requestsPerWindow = requestsPerWindow;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }
}
