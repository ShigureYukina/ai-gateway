package io.gateway.oss.admin.quota;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Shared helper methods for Postgres-based stores.
 * <p>
 * Extracted to eliminate copy-paste duplication between
 * {@link PostgresClientUsageStore} and {@link PostgresClientCostStore}.
 */
final class PostgresStoreHelper {

    private PostgresStoreHelper() {
    }

    /**
     * Generates a comma-separated list of {@code count} JDBC placeholder {@code ?} tokens.
     */
    static String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 2 - 1);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    /**
     * Builds a flat list of query parameters for batch queries keyed by day.
     * <p>
     * The result starts with {@code namespace} followed by one
     * {@code clientId + ":" + dayString} entry per client id.
     */
    static List<Object> batchParams(String namespace, Collection<String> clientIds, Instant now) {
        String dayStr = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
        List<Object> params = new ArrayList<>(clientIds.size() + 1);
        params.add(namespace);
        for (String clientId : clientIds) {
            params.add(clientId + ":" + dayStr);
        }
        return params;
    }
}
