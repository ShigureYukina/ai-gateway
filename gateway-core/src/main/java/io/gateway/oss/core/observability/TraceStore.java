package io.gateway.oss.core.observability;

import java.util.List;

public interface TraceStore {
    void save(TraceRecord record);
    TraceRecord getByRequestId(String requestId);
    List<TraceRecord> getRecent(int limit);

    default void resetForTests() {
    }
}
