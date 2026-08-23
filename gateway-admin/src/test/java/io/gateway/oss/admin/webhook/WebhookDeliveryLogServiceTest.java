package io.gateway.oss.admin.webhook;

import io.gateway.oss.admin.entity.WebhookDeliveryLogEntity;
import io.gateway.oss.admin.repository.WebhookDeliveryLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryLogServiceTest {

    @Mock
    private WebhookDeliveryLogRepository webhookDeliveryLogRepository;

    @InjectMocks
    private WebhookDeliveryLogService webhookDeliveryLogService;

    @Test
    void createPendingShouldCreateEntityWithPendingStatus() {
        when(webhookDeliveryLogRepository.save(any(WebhookDeliveryLogEntity.class))).thenAnswer(invocation -> {
            WebhookDeliveryLogEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });

        WebhookDeliveryLogEntity result = webhookDeliveryLogService.createPending(11L, "alert.triggered", "evt-1", "{\"ok\":true}");

        assertEquals(1L, result.getId());
        assertEquals(11L, result.getEndpointId());
        assertEquals("alert.triggered", result.getEventType());
        assertEquals("evt-1", result.getEventId());
        assertEquals("pending", result.getStatus());
        assertEquals(0, result.getAttempts());
        assertEquals("{\"ok\":true}", result.getPayload());
        verify(webhookDeliveryLogRepository).save(any(WebhookDeliveryLogEntity.class));
    }

    @Test
    void markDeliveredShouldUpdateToDeliveredWithStatus() {
        WebhookDeliveryLogEntity entity = deliveryLogEntity();

        webhookDeliveryLogService.markDelivered(entity, 204, 1);

        assertEquals("delivered", entity.getStatus());
        assertEquals(204, entity.getHttpStatus());
        assertEquals(1, entity.getAttempts());
        assertNull(entity.getLastError());
        assertNotNull(entity.getDeliveredAt());
        verify(webhookDeliveryLogRepository).save(entity);
    }

    @Test
    void recordAttemptShouldPersistLatestAttempts() {
        WebhookDeliveryLogEntity entity = deliveryLogEntity();

        webhookDeliveryLogService.recordAttempt(entity, 2);

        assertEquals(2, entity.getAttempts());
        verify(webhookDeliveryLogRepository).save(entity);
    }

    @Test
    void markFailedShouldUpdateToFailedWithError() {
        WebhookDeliveryLogEntity entity = deliveryLogEntity();

        webhookDeliveryLogService.markFailed(entity, 500, "timeout", 2);

        assertEquals("failed", entity.getStatus());
        assertEquals(500, entity.getHttpStatus());
        assertEquals(2, entity.getAttempts());
        assertEquals("timeout", entity.getLastError());
        verify(webhookDeliveryLogRepository).save(entity);
    }

    @Test
    void listRecentShouldReturnSortedDeliveryViewsFromRepository() {
        Instant newer = Instant.now();
        Instant older = newer.minusSeconds(60);
        WebhookDeliveryLogEntity first = deliveryLogEntity();
        first.setId(1L);
        first.setEndpointId(10L);
        first.setEventType("alert.triggered");
        first.setEventId("evt-new");
        first.setStatus("delivered");
        first.setHttpStatus(200);
        first.setAttempts(1);
        first.setCreatedAt(newer);
        first.setDeliveredAt(newer);

        WebhookDeliveryLogEntity second = deliveryLogEntity();
        second.setId(2L);
        second.setEndpointId(20L);
        second.setEventType("alert.triggered");
        second.setEventId("evt-old");
        second.setStatus("failed");
        second.setHttpStatus(500);
        second.setAttempts(3);
        second.setLastError("boom");
        second.setCreatedAt(older);

        when(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(first, second));

        List<?> result = webhookDeliveryLogService.listRecent();

        assertEquals(2, result.size());
        assertEquals("evt-new", invokeAccessor(result.get(0), "eventId"));
        assertEquals("delivered", invokeAccessor(result.get(0), "status"));
        assertEquals("evt-old", invokeAccessor(result.get(1), "eventId"));
        assertEquals("failed", invokeAccessor(result.get(1), "status"));
        verify(webhookDeliveryLogRepository).findTop100ByOrderByCreatedAtDesc();
    }

    @Test
    void listRecentReactiveShouldWrapCorrectly() {
        WebhookDeliveryLogEntity entity = deliveryLogEntity();
        entity.setId(1L);
        entity.setEndpointId(10L);
        entity.setEventType("alert.triggered");
        entity.setEventId("evt-1");
        entity.setStatus("pending");
        when(webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(entity));

        List<?> result = webhookDeliveryLogService.listRecentReactive().block();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("evt-1", invokeAccessor(result.get(0), "eventId"));
        verify(webhookDeliveryLogRepository).findTop100ByOrderByCreatedAtDesc();
    }

    private static Object invokeAccessor(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static WebhookDeliveryLogEntity deliveryLogEntity() {
        WebhookDeliveryLogEntity entity = new WebhookDeliveryLogEntity();
        entity.setEndpointId(10L);
        entity.setEventType("alert.triggered");
        entity.setEventId("evt-1");
        entity.setStatus("pending");
        entity.setAttempts(0);
        entity.setPayload("{}");
        return entity;
    }
}
