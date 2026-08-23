package io.gateway.oss.admin.limit;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public interface ClientTpmStore {

    long currentMinuteUsage(String clientId, Instant now);

    long reserve(String clientId, long tokens, long tpmLimit, Instant now);

    void adjust(String clientId, long deltaTokens, Instant now);

    /**
     * Batch version of {@link #currentMinuteUsage(String, Instant)}.
     * Default implementation loops per client — override in Postgres store for single-query batch.
     */
    default Map<String, Long> batchCurrentMinuteUsage(Collection<String> clientIds, Instant now) {
        Map<String, Long> result = new HashMap<>(clientIds.size());
        for (String clientId : clientIds) {
            result.put(clientId, currentMinuteUsage(clientId, now));
        }
        return result;
    }
}
