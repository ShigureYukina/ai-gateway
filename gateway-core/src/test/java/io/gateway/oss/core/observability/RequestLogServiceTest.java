package io.gateway.oss.core.observability;

import io.gateway.oss.core.config.ConfigStore;
import io.gateway.oss.core.config.InMemoryConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLogServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void initLoadsLatestEntriesIntoMemoryInTimestampOrder() throws Exception {
        InMemoryConfigStore store = new InMemoryConfigStore();
        RequestLogService service = new RequestLogService(store, objectMapper);
        Instant base = Instant.parse("2026-06-06T00:00:00Z");

        for (int i = 0; i < 1005; i++) {
            RequestLogService.RequestLogEntry entry = entry("req-" + i, "client-1", "gpt-4o", 200, base.plusSeconds(i));
            store.save("request-logs", entry.requestId(), objectMapper.writeValueAsString(entry)).block();
        }

        service.init();

        List<RequestLogService.RequestLogEntry> recent = service.getRecent(1005);
        assertEquals(RequestLogService.MAX_ENTRIES, recent.size());
        assertEquals("req-5", recent.get(0).requestId());
        assertEquals("req-1004", recent.get(recent.size() - 1).requestId());
    }

    @Test
    void recordPersistsAndSupportsClientFallbackFiltering() {
        InMemoryConfigStore store = new InMemoryConfigStore();
        RequestLogService service = new RequestLogService(store, objectMapper);
        Instant now = Instant.parse("2026-06-06T10:15:30Z");

        RequestLogService.RequestLogEntry legacyEntry = new RequestLogService.RequestLogEntry(
                "req-legacy", "masked-client", null, "gpt-4o", "openai", "route-1", "chat",
                200, 12L, now.minusSeconds(5), "stream", 20L, 10L, 10L, 0.2, null);
        RequestLogService.RequestLogEntry clientKeyEntry = new RequestLogService.RequestLogEntry(
                "req-key", "masked-client", "client-123", "gpt-4o", "openai", "route-1", "chat",
                429, 18L, now, "non-stream", null, null, null, null, "rate_limited");

        service.record(legacyEntry);
        service.record(clientKeyEntry);

        assertEquals(List.of(clientKeyEntry), service.getByClient("client-123", 10));
        assertEquals(List.of(legacyEntry), service.getByClient("masked-client", 10));

        List<RequestLogService.RequestLogEntry> filtered = service.getFiltered(
                now.minusSeconds(10), now.plusSeconds(1), "gpt-4o", "client-123", 429, 0, 10);
        assertEquals(List.of(clientKeyEntry), filtered);
        assertEquals(1L, service.countFiltered(now.minusSeconds(10), now.plusSeconds(1), "gpt-4o", "client-123", 429));
        assertNotNull(store.load("request-logs", "req-key").block());

        String compactLegacy = store.load("request-logs", "req-legacy").block();
        assertNotNull(compactLegacy);
        assertTrue(compactLegacy.contains("\"r\":\"req-legacy\""));
        assertFalse(compactLegacy.contains("requestId"));
        assertFalse(compactLegacy.contains("\"k\":"));

        String compactClientKey = store.load("request-logs", "req-key").block();
        assertNotNull(compactClientKey);
        assertTrue(compactClientKey.contains("\"k\":\"client-123\""));
    }

    @Test
    void record_replacesExistingRequestIdAndKeepsNewestAtFront() {
        InMemoryConfigStore store = new InMemoryConfigStore();
        RequestLogService service = new RequestLogService(store, objectMapper);
        Instant base = Instant.parse("2026-06-06T10:15:30Z");

        RequestLogService.RequestLogEntry older = new RequestLogService.RequestLogEntry(
                "req-dup", "masked-old", "client-123", "gpt-4o", "openai", "route-1", "chat",
                200, 12L, base.minusSeconds(5), "stream", 20L, 10L, 10L, 0.2, null);
        RequestLogService.RequestLogEntry newer = new RequestLogService.RequestLogEntry(
                "req-dup", "masked-new", "client-123", "gpt-4.1-mini", "openai", "route-2", "chat",
                429, 18L, base, "non-stream", null, null, null, null, "rate_limited");

        service.record(older);
        service.record(newer);

        assertEquals(List.of(newer), service.getRecent(10));
        assertEquals(newer, service.getByRequestId("req-dup").block());
    }

    @Test
    void getByRequestIdFallsBackToPersistenceAndCachesResult() throws Exception {
        InMemoryConfigStore store = new InMemoryConfigStore();
        RequestLogService service = new RequestLogService(store, objectMapper);
        RequestLogService.RequestLogEntry persisted = entry("req-persisted", "client-9", "model-x", 200,
                Instant.parse("2026-06-06T11:00:00Z"));
        store.save("request-logs", persisted.requestId(), objectMapper.writeValueAsString(persisted)).block();

        RequestLogService.RequestLogEntry loaded = service.getByRequestId("req-persisted").block();

        assertEquals(persisted, loaded);
        assertEquals(List.of(persisted), service.getRecent(10));
    }

    @Test
    void recordRetriesPersistenceWithoutChangingExternalSemantics() {
        FlakyConfigStore store = new FlakyConfigStore(2);
        RequestLogService service = new RequestLogService(store, objectMapper);
        RequestLogService.RequestLogEntry entry = entry("req-retry", "client-7", "model-y", 200,
                Instant.parse("2026-06-06T12:00:00Z"));

        service.record(entry);

        assertTrue(store.saveAttempts().get("req-retry").get() >= 3);
        assertEquals(entry, service.getByRequestId("req-retry").block());
    }

    private RequestLogService.RequestLogEntry entry(String requestId,
                                                    String clientId,
                                                    String model,
                                                    int status,
                                                    Instant timestamp) {
        return new RequestLogService.RequestLogEntry(
                requestId, clientId, clientId, model, "openai", "route-1", "chat",
                status, 15L, timestamp, "stream", 20L, 10L, 10L, 0.2, null);
    }

    private static final class FlakyConfigStore implements ConfigStore {
        private final Map<String, String> storage = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> saveAttempts = new ConcurrentHashMap<>();
        private final int failuresBeforeSuccess;

        private FlakyConfigStore(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public Mono<Void> save(String configType, String key, String jsonValue) {
            int attempt = saveAttempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                return Mono.error(new IllegalStateException("simulated failure"));
            }
            storage.put(key, jsonValue);
            return Mono.empty();
        }

        @Override
        public Mono<String> load(String configType, String key) {
            String value = storage.get(key);
            return value == null ? Mono.empty() : Mono.just(value);
        }

        @Override
        public Mono<Void> delete(String configType, String key) {
            storage.remove(key);
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> saveIfAbsentOrReplaceExpired(String configType, String key, String jsonValue, Duration ttl) {
            return save(configType, key, jsonValue).thenReturn(true);
        }

        @Override
        public Mono<Map<String, String>> loadAll(String configType) {
            return Mono.just(Map.copyOf(storage));
        }

        Map<String, AtomicInteger> saveAttempts() {
            return saveAttempts;
        }
    }
}
