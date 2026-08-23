package io.gateway.oss.admin.webhook;

import io.gateway.oss.admin.entity.WebhookEndpointEntity;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.admin.repository.WebhookEndpointRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;

@Service
public class WebhookEndpointService {

    private final WebhookEndpointRepository webhookEndpointRepository;

    public WebhookEndpointService(WebhookEndpointRepository webhookEndpointRepository) {
        this.webhookEndpointRepository = webhookEndpointRepository;
    }

    public List<WebhookEndpointView> list() {
        return webhookEndpointRepository.findAll().stream().map(this::toView).toList();
    }

    public WebhookEndpointView get(Long id) {
        return webhookEndpointRepository.findById(id)
                .map(this::toView)
                .orElseThrow(() -> new GatewayException(HttpStatus.NOT_FOUND, "webhook_not_found", "Webhook endpoint not found: " + id));
    }

    public List<WebhookEndpointEntity> listEntities() {
        return webhookEndpointRepository.findAll();
    }

    public Mono<List<WebhookEndpointView>> listReactive() {
        return Mono.fromCallable(this::list).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<WebhookEndpointView> getReactive(Long id) {
        return Mono.fromCallable(() -> get(id)).subscribeOn(Schedulers.boundedElastic());
    }

    public WebhookEndpointView create(UpsertWebhookEndpointCommand command) {
        WebhookEndpointEntity entity = new WebhookEndpointEntity();
        apply(entity, command);
        return toView(webhookEndpointRepository.save(entity));
    }

    public WebhookEndpointView update(Long id, UpsertWebhookEndpointCommand command) {
        WebhookEndpointEntity entity = webhookEndpointRepository.findById(id)
                .orElseThrow(() -> new GatewayException(HttpStatus.NOT_FOUND, "webhook_not_found", "Webhook endpoint not found: " + id));
        apply(entity, command);
        entity.setUpdatedAt(Instant.now());
        return toView(webhookEndpointRepository.save(entity));
    }

    public boolean delete(Long id) {
        if (!webhookEndpointRepository.existsById(id)) {
            return false;
        }
        webhookEndpointRepository.deleteById(id);
        return true;
    }

    private void apply(WebhookEndpointEntity entity, UpsertWebhookEndpointCommand command) {
        entity.setName(command.name());
        entity.setUrl(command.url());
        entity.setSecret(command.secret());
        entity.setEnabled(command.enabled());
        entity.setEventTypes(normalizeEventTypes(command.eventTypes()));
        entity.setRetryMax(command.retryMax());
        entity.setTimeoutMs(command.timeoutMs());
    }

    private List<String> normalizeEventTypes(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return List.of("*");
        }
        return eventTypes.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
    }

    private WebhookEndpointView toView(WebhookEndpointEntity entity) {
        return new WebhookEndpointView(
                entity.getId(),
                entity.getName(),
                entity.getUrl(),
                entity.getSecret(),
                entity.isEnabled(),
                entity.getEventTypes() == null || entity.getEventTypes().isEmpty() ? List.of("*") : List.copyOf(entity.getEventTypes()),
                entity.getRetryMax(),
                entity.getTimeoutMs(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public record UpsertWebhookEndpointCommand(
            String name,
            String url,
            String secret,
            boolean enabled,
            List<String> eventTypes,
            int retryMax,
            int timeoutMs
    ) {
    }

    public record WebhookEndpointView(
            Long id,
            String name,
            String url,
            String secret,
            boolean enabled,
            List<String> eventTypes,
            int retryMax,
            int timeoutMs,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
