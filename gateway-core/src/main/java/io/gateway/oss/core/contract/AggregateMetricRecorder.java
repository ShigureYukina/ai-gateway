package io.gateway.oss.core.contract;

import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.security.ClientPrincipal;

import java.time.Instant;

/**
 * Optional aggregate metric recorder — implemented by gateway-admin's AggregateReportingService.
 * When absent from the classpath, aggregate metrics are not recorded (no-op).
 */
public interface AggregateMetricRecorder {

    void recordSuccess(String requestId,
                       ClientPrincipal principal,
                       ResolvedRoute route,
                       String model,
                       long usageTokens,
                       Double costUsd,
                       Instant now);

    void recordFailureStatus(String requestId, int status, Instant now);
}
