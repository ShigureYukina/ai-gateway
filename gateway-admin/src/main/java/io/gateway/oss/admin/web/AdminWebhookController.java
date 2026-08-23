package io.gateway.oss.admin.web;

import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.admin.webhook.WebhookDeliveryLogService;
import io.gateway.oss.admin.webhook.WebhookEndpointService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@RestController
@Validated
@RequestMapping("/admin/webhooks")
public class AdminWebhookController extends AdminBaseController {

    private final WebhookEndpointService webhookEndpointService;
    private final WebhookDeliveryLogService webhookDeliveryLogService;

    public AdminWebhookController(ClientAuthService clientAuthService,
                                  WebhookEndpointService webhookEndpointService,
                                  WebhookDeliveryLogService webhookDeliveryLogService) {
        super(clientAuthService);
        this.webhookEndpointService = webhookEndpointService;
        this.webhookDeliveryLogService = webhookDeliveryLogService;
    }

    @GetMapping
    public Mono<WebhookEndpointsResponse> list(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        return webhookEndpointService.listReactive()
                .map(endpoints -> new WebhookEndpointsResponse(Instant.now(), endpoints.stream().map(this::toEndpointView).toList()));
    }

    @GetMapping("/{id}")
    public Mono<WebhookEndpointView> get(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable Long id) {
        requireAdminAccess(authorizationHeader);
        return webhookEndpointService.getReactive(id)
                .map(this::toEndpointView);
    }

    @PostMapping
    public Mono<ResponseEntity<WebhookEndpointView>> create(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Valid @RequestBody UpsertWebhookEndpointRequest request) {
        requireAdminAccess(authorizationHeader);
        return Mono.fromCallable(() -> webhookEndpointService.create(toCommand(request)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(toEndpointView(created)));
    }

    @PutMapping("/{id}")
    public Mono<WebhookEndpointView> update(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable Long id,
            @Valid @RequestBody UpsertWebhookEndpointRequest request) {
        requireAdminAccess(authorizationHeader);
        return Mono.fromCallable(() -> webhookEndpointService.update(id, toCommand(request)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(this::toEndpointView);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable Long id) {
        requireAdminAccess(authorizationHeader);
        return Mono.fromCallable(() -> webhookEndpointService.delete(id))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(deleted -> deleted
                        ? ResponseEntity.noContent().build()
                        : ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/deliveries")
    public Mono<WebhookDeliveriesResponse> deliveries(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        requireAdminAccess(authorizationHeader);
        return webhookDeliveryLogService.listRecentReactive()
                .map(deliveries -> new WebhookDeliveriesResponse(Instant.now(), deliveries.stream().map(this::toDeliveryView).toList()));
    }

    private WebhookEndpointService.UpsertWebhookEndpointCommand toCommand(UpsertWebhookEndpointRequest request) {
        return new WebhookEndpointService.UpsertWebhookEndpointCommand(
                request.name(),
                request.url(),
                request.secret(),
                request.enabled(),
                request.eventTypes(),
                request.retryMax(),
                request.timeoutMs()
        );
    }

    private WebhookEndpointView toEndpointView(WebhookEndpointService.WebhookEndpointView entity) {
        return new WebhookEndpointView(
                entity.id(),
                entity.name(),
                entity.url(),
                maskSecret(entity.secret()),
                entity.enabled(),
                entity.eventTypes(),
                entity.retryMax(),
                entity.timeoutMs(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "****";
        }
        if (secret.length() <= 2) {
            return "****";
        }
        return "****" + secret.substring(secret.length() - 2);
    }

    private WebhookDeliveryView toDeliveryView(WebhookDeliveryLogService.WebhookDeliveryView entity) {
        return new WebhookDeliveryView(
                entity.id(),
                entity.endpointId(),
                entity.eventType(),
                entity.eventId(),
                entity.status(),
                entity.httpStatus(),
                entity.attempts(),
                entity.lastError(),
                entity.createdAt(),
                entity.deliveredAt()
        );
    }

    public record UpsertWebhookEndpointRequest(
            @NotBlank String name,
            @NotBlank String url,
            String secret,
            boolean enabled,
            List<String> eventTypes,
            @Min(0) @Max(20) int retryMax,
            @Min(100) @Max(120000) int timeoutMs
    ) {
    }

    public record WebhookEndpointsResponse(Instant generatedAt, List<WebhookEndpointView> endpoints) {
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

    public record WebhookDeliveriesResponse(Instant generatedAt, List<WebhookDeliveryView> deliveries) {
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
