package io.gateway.oss.core.upstream;

import java.time.Instant;
import java.util.Map;

public interface ProviderRuntimeStateStore {

    ProviderRuntimeState get(String provider);

    void save(String provider, ProviderRuntimeState state);

    Map<String, ProviderRuntimeState> getAll();

    record ProviderRuntimeState(
            boolean runtimeAvailable,
            Instant lastCheckedAt,
            Instant lastSuccessAt,
            int consecutiveFailures,
            int consecutiveSuccesses,
            Integer httpStatus,
            Long latencyMs,
            String reason
    ) {
        public static ProviderRuntimeState unknown() {
            return new ProviderRuntimeState(true, null, null, 0, 0, null, null, null);
        }
    }
}
