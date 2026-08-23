package io.gateway.oss.core.contract;

import java.time.Duration;

public interface LimitConfigView {
    int getRequestsPerWindow();
    Duration getWindow();
}
