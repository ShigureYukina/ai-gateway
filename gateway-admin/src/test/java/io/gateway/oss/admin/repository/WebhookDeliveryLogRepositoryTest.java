package io.gateway.oss.admin.repository;

import io.gateway.oss.admin.entity.WebhookDeliveryLogEntity;
import io.gateway.oss.admin.entity.WebhookEndpointEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebhookDeliveryLogRepositoryTest extends RepositoryDataJpaTestSupport {

    @Autowired
    private WebhookDeliveryLogRepository logRepository;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    private Long endpointId;

    @BeforeEach
    void setUp() {
        var endpoint = new WebhookEndpointEntity();
        endpoint.setName("log-test-endpoint");
        endpoint.setUrl("https://hooks.test.com/log");
        endpoint.setEnabled(true);
        endpoint.setRetryMax(3);
        endpoint.setTimeoutMs(5000);
        endpointId = endpointRepository.save(endpoint).getId();
    }

    @Test
    void shouldSaveDeliveryLog() {
        var log = createLog(endpointId, "provider.updated", "delivered", 200);
        var saved = logRepository.save(log);

        assertNotNull(saved.getId());
        assertEquals("provider.updated", saved.getEventType());
        assertEquals("delivered", saved.getStatus());
        assertEquals(200, saved.getHttpStatus());
    }

    @Test
    void shouldFindTop100ByOrderByCreatedAtDesc() {
        // Insert 3 logs
        for (int i = 0; i < 3; i++) {
            var log = createLog(endpointId, "event-" + i, "delivered", 200);
            logRepository.save(log);
        }

        List<WebhookDeliveryLogEntity> recent = logRepository.findTop100ByOrderByCreatedAtDesc();
        assertEquals(3, recent.size());
        assertTrue(recent.get(0).getCreatedAt().isAfter(recent.get(1).getCreatedAt())
                || recent.get(0).getCreatedAt().equals(recent.get(1).getCreatedAt()));
    }

    @Test
    void shouldReturnEmptyListWhenNoLogs() {
        assertTrue(logRepository.findTop100ByOrderByCreatedAtDesc().isEmpty());
    }

    @Test
    void shouldTrackFailedDeliveries() {
        var log = createLog(endpointId, "provider.updated", "failed", null);
        log.setAttempts(3);
        log.setLastError("Connection timeout");
        logRepository.save(log);

        var found = logRepository.findTop100ByOrderByCreatedAtDesc();
        assertEquals(1, found.size());
        assertEquals("failed", found.getFirst().getStatus());
        assertEquals(3, found.getFirst().getAttempts());
        assertEquals("Connection timeout", found.getFirst().getLastError());
    }

    private static WebhookDeliveryLogEntity createLog(Long endpointId, String eventType,
                                                       String status, Integer httpStatus) {
        var e = new WebhookDeliveryLogEntity();
        e.setEndpointId(endpointId);
        e.setEventType(eventType);
        e.setEventId("evt-" + System.nanoTime());
        e.setStatus(status);
        e.setHttpStatus(httpStatus);
        e.setAttempts(1);
        e.setCreatedAt(Instant.now());
        return e;
    }
}
