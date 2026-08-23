package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.ResilienceConfig;

import java.time.Instant;

public interface RouteStateStore {

    boolean isAvailable(String routeId, Instant now);

    void recordSuccess(String routeId);

    void recordRetryableFailure(String routeId, Instant now, ResilienceConfig config);
}
