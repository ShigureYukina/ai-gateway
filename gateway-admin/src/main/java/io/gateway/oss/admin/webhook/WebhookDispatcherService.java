package io.gateway.oss.admin.webhook;

import io.gateway.oss.admin.entity.WebhookEndpointEntity;
import io.gateway.oss.admin.web.alerts.AdminAlertsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class WebhookDispatcherService {

    private static final String ALERT_TRIGGERED = "alert.triggered";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcherService.class);

    private final WebhookEndpointService webhookEndpointService;
    private final WebhookDeliveryLogService webhookDeliveryLogService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final boolean dispatcherEnabled;

    public WebhookDispatcherService(WebhookEndpointService webhookEndpointService,
                                    WebhookDeliveryLogService webhookDeliveryLogService,
                                    WebClient.Builder webClientBuilder,
                                    ObjectMapper objectMapper,
                                    @Value("${gateway.webhook.dispatcher.enabled:true}") boolean dispatcherEnabled) {
        this.webhookEndpointService = webhookEndpointService;
        this.webhookDeliveryLogService = webhookDeliveryLogService;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.dispatcherEnabled = dispatcherEnabled;
    }

    public void triggerAlertTriggered(AdminAlertsService.AlertsView alertsView) {
        // 允许在不需要 webhook 副作用的环境关闭异步派发，生产默认保持开启。
        if (!dispatcherEnabled) {
            return;
        }
        Mono.fromRunnable(() -> dispatchAlertTriggered(alertsView))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        ignored -> { },
                        ex -> log.warn("webhook_dispatch_schedule_failed: {}", ex.toString(), ex)
                );
    }

    private void dispatchAlertTriggered(AdminAlertsService.AlertsView alertsView) {
        try {
            String eventId = UUID.randomUUID().toString();
            AlertTriggeredEvent event = new AlertTriggeredEvent(eventId, ALERT_TRIGGERED, Instant.now(), alertsView.active());
            String payload = toJson(event);
            List<WebhookEndpointEntity> endpoints = webhookEndpointService.listEntities();
            for (WebhookEndpointEntity endpoint : endpoints) {
                if (!endpoint.isEnabled() || !matchesEvent(endpoint, ALERT_TRIGGERED)) {
                    continue;
                }
                var logEntry = webhookDeliveryLogService.createPending(endpoint.getId(), ALERT_TRIGGERED, eventId, payload);
                AtomicInteger attempts = new AtomicInteger();
                Mono.defer(() -> {
                            int currentAttempt = attempts.incrementAndGet();
                            webhookDeliveryLogService.recordAttempt(logEntry, currentAttempt);
                            return sendOnce(endpoint, payload);
                        })
                        .retryWhen(Retry.backoff(Math.max(0, endpoint.getRetryMax()), Duration.ofSeconds(1))
                                .maxBackoff(Duration.ofSeconds(10))
                                .jitter(0.5))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                // 投递完成信号来自 WebClient 事件循环，JPA 落库移到 boundedElastic（审查 C3）
                                status -> Mono.fromRunnable(() -> webhookDeliveryLogService.markDelivered(logEntry, status, attempts.get()))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(),
                                ex -> Mono.fromRunnable(() -> webhookDeliveryLogService.markFailed(logEntry, null, ex.getMessage(), attempts.get()))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe()
                        );
            }
        } catch (Exception ex) {
            log.warn("webhook_dispatch_failed: {}", ex.toString(), ex);
        }
    }

    private Mono<Integer> sendOnce(WebhookEndpointEntity endpoint, String payload) {
        Duration timeout = Duration.ofMillis(Math.max(100, endpoint.getTimeoutMs()));
        var request = webClientBuilder.build()
                .post()
                .uri(endpoint.getUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Webhook-Event", ALERT_TRIGGERED);
        String secret = endpoint.getSecret();
        if (secret != null && !secret.isBlank()) {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            request.header("X-Webhook-Timestamp", timestamp)
                    .header("X-Webhook-Signature", sign(secret, timestamp, payload));
        }
        return request
                .bodyValue(payload)
                .exchangeToMono(resp -> Mono.just(resp.statusCode().value()))
                .timeout(timeout)
                .flatMap(status -> {
                    if (status >= 200 && status < 300) {
                        return Mono.just(status);
                    }
                    return Mono.error(new IllegalStateException("Webhook returned status " + status));
                });
    }

    private String sign(String secret, String timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign webhook payload", e);
        }
    }

    private boolean matchesEvent(WebhookEndpointEntity endpoint, String eventType) {
        List<String> configured = endpoint.getEventTypes() == null || endpoint.getEventTypes().isEmpty()
                ? List.of("*")
                : endpoint.getEventTypes();
        return configured.contains("*") || configured.contains(eventType);
    }

    private String toJson(AlertTriggeredEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize webhook payload", e);
        }
    }

    public record AlertTriggeredEvent(
            String eventId,
            String eventType,
            Instant triggeredAt,
            List<AdminAlertsService.AlertView> alerts
    ) {
    }
}
