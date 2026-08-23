package io.gateway.oss.core.observability;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;

/**
 * 请求日志内存缓冲区。
 */
final class RequestLogBuffer {

    private final ConcurrentLinkedDeque<RequestLogService.RequestLogEntry> entries = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, RequestLogService.RequestLogEntry> byRequestId = new ConcurrentHashMap<>();
    private final int maxEntries;

    RequestLogBuffer(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    void add(RequestLogService.RequestLogEntry entry) {
        RequestLogService.RequestLogEntry existing = byRequestId.put(entry.requestId(), entry);
        if (existing != null) {
            entries.remove(existing);
        }
        entries.addFirst(entry);
        while (entries.size() > maxEntries) {
            RequestLogService.RequestLogEntry removed = entries.pollLast();
            if (removed == null) {
                break;
            }
            byRequestId.remove(removed.requestId(), removed);
        }
    }

    List<RequestLogService.RequestLogEntry> getRecent(int limit) {
        return entries.stream().limit(limit).toList();
    }

    RequestLogService.RequestLogEntry findByRequestId(String requestId) {
        return byRequestId.get(requestId);
    }

    List<RequestLogService.RequestLogEntry> getByModel(String model, int limit) {
        return entries.stream()
                .filter(entry -> model.equals(entry.model()))
                .limit(limit)
                .toList();
    }

    List<RequestLogService.RequestLogEntry> getByClient(String clientId, int limit) {
        return entries.stream()
                .filter(entry -> matchesClientKey(entry, clientId))
                .limit(limit)
                .toList();
    }

    List<RequestLogService.RequestLogEntry> getFiltered(Instant from,
                                                        Instant to,
                                                        String model,
                                                        String client,
                                                        Integer status,
                                                        int offset,
                                                        int limit) {
        return filteredStream(from, to, model, client, status)
                .skip(offset)
                .limit(limit)
                .toList();
    }

    long countFiltered(Instant from, Instant to, String model, String client, Integer status) {
        return filteredStream(from, to, model, client, status).count();
    }

    List<RequestLogService.RequestLogEntry> getByClientFiltered(String clientId, Instant from, Instant to, int limit) {
        return entries.stream()
                .filter(entry -> matchesClientKey(entry, clientId))
                .filter(entry -> from == null || (entry.timestamp() != null && !entry.timestamp().isBefore(from)))
                .filter(entry -> to == null || (entry.timestamp() != null && entry.timestamp().isBefore(to)))
                .limit(limit)
                .toList();
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.clear();
        byRequestId.clear();
    }

    private Stream<RequestLogService.RequestLogEntry> filteredStream(Instant from,
                                                                     Instant to,
                                                                     String model,
                                                                     String client,
                                                                     Integer status) {
        return entries.stream()
                .filter(entry -> from == null || (entry.timestamp() != null && !entry.timestamp().isBefore(from)))
                .filter(entry -> to == null || (entry.timestamp() != null && entry.timestamp().isBefore(to)))
                .filter(entry -> model == null || model.equals(entry.model()))
                .filter(entry -> client == null || matchesClientKey(entry, client))
                .filter(entry -> status == null || entry.status() == status);
    }

    private boolean matchesClientKey(RequestLogService.RequestLogEntry entry, String clientKey) {
        if (clientKey == null) {
            return false;
        }
        // Backward compatibility: older records only have masked clientId.
        if (entry.clientKey() != null) {
            return clientKey.equals(entry.clientKey());
        }
        return clientKey.equals(entry.clientId());
    }
}
