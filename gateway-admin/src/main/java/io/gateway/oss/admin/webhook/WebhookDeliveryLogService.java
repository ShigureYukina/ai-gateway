package io.gateway.oss.admin.webhook;

import io.gateway.oss.admin.entity.WebhookDeliveryLogEntity;
import io.gateway.oss.admin.repository.WebhookDeliveryLogRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;

@Service
public class WebhookDeliveryLogService {

    private final WebhookDeliveryLogRepository webhookDeliveryLogRepository;

    public WebhookDeliveryLogService(WebhookDeliveryLogRepository webhookDeliveryLogRepository) {
        this.webhookDeliveryLogRepository = webhookDeliveryLogRepository;
    }

    public WebhookDeliveryLogEntity createPending(Long endpointId, String eventType, String eventId, String payload) {
        WebhookDeliveryLogEntity entity = new WebhookDeliveryLogEntity();
        entity.setEndpointId(endpointId);
        entity.setEventType(eventType);
        entity.setEventId(eventId);
        entity.setStatus("pending");
        entity.setAttempts(0);
        entity.setPayload(payload);
        return webhookDeliveryLogRepository.save(entity);
    }

    public void recordAttempt(WebhookDeliveryLogEntity entity, int attempts) {
        entity.setAttempts(attempts);
        webhookDeliveryLogRepository.save(entity);
    }

    public void markDelivered(WebhookDeliveryLogEntity entity, Integer httpStatus, int attempts) {
        entity.setStatus("delivered");
        entity.setHttpStatus(httpStatus);
        entity.setAttempts(attempts);
        entity.setLastError(null);
        entity.setDeliveredAt(Instant.now());
        webhookDeliveryLogRepository.save(entity);
    }

    public void markFailed(WebhookDeliveryLogEntity entity, Integer httpStatus, String error, int attempts) {
        entity.setStatus("failed");
        entity.setHttpStatus(httpStatus);
        entity.setAttempts(attempts);
        entity.setLastError(error);
        webhookDeliveryLogRepository.save(entity);
    }

    public List<WebhookDeliveryView> listRecent() {
        return webhookDeliveryLogRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::toView)
                .toList();
    }

    public Mono<List<WebhookDeliveryView>> listRecentReactive() {
        return Mono.fromCallable(this::listRecent).subscribeOn(Schedulers.boundedElastic());
    }

    private WebhookDeliveryView toView(WebhookDeliveryLogEntity entity) {
        return new WebhookDeliveryView(
                entity.getId(),
                entity.getEndpointId(),
                entity.getEventType(),
                entity.getEventId(),
                entity.getStatus(),
                entity.getHttpStatus(),
                entity.getAttempts(),
                entity.getLastError(),
                entity.getCreatedAt(),
                entity.getDeliveredAt()
        );
    }

    public record WebhookDeliveryView(
            Long id,
            Long endpointId,
            String eventType,
            String eventId,
            String status,
            Integer httpStatus,
            int attempts,
            String lastError,
            Instant createdAt,
            Instant deliveredAt
    ) {
    }
}
