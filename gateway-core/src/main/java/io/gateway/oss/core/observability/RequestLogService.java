package io.gateway.oss.core.observability;

import io.gateway.oss.core.config.ConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class RequestLogService {
    private static final Logger log = LoggerFactory.getLogger(RequestLogService.class);
    static final int MAX_ENTRIES = 1000;
    static final int MAX_PERSIST_RETRIES = 3;

    private final RequestLogBuffer buffer;
    private final RequestLogPersistence persistence;

    @Autowired
    public RequestLogService(ConfigStore configStore, ObjectMapper objectMapper) {
        this(new RequestLogBuffer(MAX_ENTRIES),
                new RequestLogPersistence(
                        Objects.requireNonNull(configStore),
                        new RequestLogCodec(Objects.requireNonNull(objectMapper)),
                        MAX_ENTRIES,
                        MAX_PERSIST_RETRIES));
    }

    @PostConstruct
    public void init() {
        persistence.init(buffer::add, () -> log.info("request_logs_loaded count={}", buffer.size()));
    }

    @PreDestroy
    public void shutdown() {
        persistence.shutdown();
    }

    public void record(RequestLogEntry entry) {
        buffer.add(entry);
        persistence.persist(entry);
    }

    public List<RequestLogEntry> getRecent(int limit) {
        return buffer.getRecent(limit);
    }

    public Mono<RequestLogEntry> getByRequestId(String requestId) {
        RequestLogEntry inMemory = buffer.findByRequestId(requestId);
        if (inMemory != null) {
            return Mono.just(inMemory);
        }

        return persistence.loadByRequestId(requestId)
                .doOnNext(buffer::add);
    }

    public List<RequestLogEntry> getByModel(String model, int limit) {
        return buffer.getByModel(model, limit);
    }

    public List<RequestLogEntry> getByClient(String clientId, int limit) {
        return buffer.getByClient(clientId, limit);
    }

    public List<RequestLogEntry> getFiltered(Instant from, Instant to, String model, String client, Integer status, int offset, int limit) {
        return buffer.getFiltered(from, to, model, client, status, offset, limit);
    }

    public long countFiltered(Instant from, Instant to, String model, String client, Integer status) {
        return buffer.countFiltered(from, to, model, client, status);
    }

    public List<RequestLogEntry> getByClientFiltered(String clientId, Instant from, Instant to, int limit) {
        return buffer.getByClientFiltered(clientId, from, to, limit);
    }

    public void resetForTests() {
        buffer.clear();
        persistence.resetForTests();
    }

    RequestLogService(RequestLogBuffer buffer, RequestLogPersistence persistence) {
        this.buffer = Objects.requireNonNull(buffer);
        this.persistence = Objects.requireNonNull(persistence);
    }

    public record RequestLogEntry(
            String requestId,
            String clientId,
            String clientKey,
            String model,
            String provider,
            String routeId,
            String scene,
            int status,
            long latencyMs,
            Instant timestamp,
            String streamMode,
            Long usageTokens,
            Long promptTokens,
            Long completionTokens,
            Double costUsd,
            String errorMessage
    ) {
    }
}
