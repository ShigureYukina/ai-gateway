package io.gateway.oss.core.observability;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class InMemoryTraceStore implements TraceStore {

    private static final int MAX_TRACES = 500;
    private final ConcurrentHashMap<String, TraceRecord> store = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();

    @Override
    public void save(TraceRecord record) {
        String id = record.requestId();
        if (id == null) return;
        store.put(id, record);
        order.remove(id);
        order.addFirst(id);
        while (order.size() > MAX_TRACES) {
            String evicted = order.pollLast();
            if (evicted != null) store.remove(evicted);
        }
    }

    @Override
    public TraceRecord getByRequestId(String requestId) {
        return store.get(requestId);
    }

    @Override
    public List<TraceRecord> getRecent(int limit) {
        return order.stream()
                .limit(limit)
                .map(store::get)
                .filter(r -> r != null)
                .toList();
    }

    @Override
    public void resetForTests() {
        store.clear();
        order.clear();
    }
}
