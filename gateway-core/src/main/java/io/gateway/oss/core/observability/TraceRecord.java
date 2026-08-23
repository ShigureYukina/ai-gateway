package io.gateway.oss.core.observability;

import java.time.Instant;

public record TraceRecord(
        String requestId,
        String clientId,
        String model,
        String provider,
        String routeId,
        String scene,
        Integer status,
        String streamMode,
        Long latencyMs,
        String errorMessage,
        String requestBody,
        String responseBody,
        Instant timestamp
) {
}
