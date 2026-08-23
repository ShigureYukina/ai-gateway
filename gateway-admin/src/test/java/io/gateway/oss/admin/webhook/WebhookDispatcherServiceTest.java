package io.gateway.oss.admin.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.gateway.oss.admin.entity.WebhookDeliveryLogEntity;
import io.gateway.oss.admin.entity.WebhookEndpointEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDispatcherServiceTest {

    @Mock
    private WebhookEndpointService webhookEndpointService;

    @Mock
    private WebhookDeliveryLogService webhookDeliveryLogService;

    private final List<ClientRequest> capturedRequests = new CopyOnWriteArrayList<>();

    private WebhookDispatcherService webhookDispatcherService;

    @BeforeEach
    void setUp() {
        webhookDispatcherService = new WebhookDispatcherService(
                webhookEndpointService,
                webhookDeliveryLogService,
                webClientBuilder(request -> {
                    capturedRequests.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                }),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                true
        );
    }

    @AfterEach
    void tearDown() {
        capturedRequests.clear();
    }

    @Test
    void triggerAlertTriggeredShouldDispatchToEnabledMatchingEndpoints() {
        WebhookEndpointEntity enabled = endpoint(1L, true, List.of("alert.triggered"), "secret-a");
        WebhookEndpointEntity disabled = endpoint(2L, false, List.of("alert.triggered"), "secret-b");
        WebhookEndpointEntity nonMatching = endpoint(3L, true, List.of("other.event"), "secret-c");
        when(webhookEndpointService.listEntities()).thenReturn(List.of(enabled, disabled, nonMatching));
        when(webhookDeliveryLogService.createPending(eq(1L), eq("alert.triggered"), any(), any()))
                .thenReturn(new WebhookDeliveryLogEntity());

        invokeTriggerAlertTriggered(webhookDispatcherService, alertsView());

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            verify(webhookDeliveryLogService).createPending(eq(1L), eq("alert.triggered"), any(), any());
            verify(webhookDeliveryLogService).markDelivered(any(WebhookDeliveryLogEntity.class), anyInt(), eq(1));
            verify(webhookDeliveryLogService, atLeastOnce()).recordAttempt(any(WebhookDeliveryLogEntity.class), eq(1));
        });
        verify(webhookDeliveryLogService, never()).createPending(eq(2L), any(), any(), any());
        verify(webhookDeliveryLogService, never()).createPending(eq(3L), any(), any(), any());
        assertEquals(1, capturedRequests.size());
    }

    @Test
    void triggerAlertTriggeredShouldSkipWhenDispatcherDisabled() {
        WebhookDispatcherService service = new WebhookDispatcherService(
                webhookEndpointService,
                webhookDeliveryLogService,
                webClientBuilder(request -> {
                    capturedRequests.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                }),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                false
        );

        invokeTriggerAlertTriggered(service, alertsView());

        verifyNoInteractions(webhookEndpointService, webhookDeliveryLogService);
        assertTrue(capturedRequests.isEmpty());
    }

    @Test
    void dispatchShouldSkipDisabledEndpoints() {
        WebhookEndpointEntity disabled = endpoint(2L, false, List.of("alert.triggered"), "secret-b");
        lenient().when(webhookEndpointService.listEntities()).thenReturn(List.of(disabled));

        invokeTriggerAlertTriggered(webhookDispatcherService, alertsView());

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            verify(webhookDeliveryLogService, never()).createPending(any(), any(), any(), any());
        });
        assertTrue(capturedRequests.isEmpty());
    }

    @Test
    void dispatchShouldSkipNonMatchingEventTypes() {
        WebhookEndpointEntity nonMatching = endpoint(3L, true, List.of("billing.updated"), null);
        lenient().when(webhookEndpointService.listEntities()).thenReturn(List.of(nonMatching));

        invokeTriggerAlertTriggered(webhookDispatcherService, alertsView());

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            verify(webhookDeliveryLogService, never()).createPending(any(), any(), any(), any());
        });
        assertTrue(capturedRequests.isEmpty());
    }

    @Test
    void hmacSigningHeaderShouldBeSetWhenSecretProvided() {
        WebhookEndpointEntity endpoint = endpoint(1L, true, List.of("alert.triggered"), "top-secret");
        when(webhookEndpointService.listEntities()).thenReturn(List.of(endpoint));
        when(webhookDeliveryLogService.createPending(eq(1L), eq("alert.triggered"), any(), any()))
                .thenReturn(new WebhookDeliveryLogEntity());

        invokeTriggerAlertTriggered(webhookDispatcherService, alertsView());

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(1, capturedRequests.size()));
        ClientRequest request = capturedRequests.get(0);
        HttpHeaders headers = request.headers();

        assertEquals("alert.triggered", headers.getFirst("X-Webhook-Event"));
        assertNotNull(headers.getFirst("X-Webhook-Timestamp"));
        String signature = headers.getFirst("X-Webhook-Signature");
        assertNotNull(signature);
        assertTrue(signature.startsWith("sha256="));
    }

    @Test
    void dispatchWithoutSecretShouldNotAddSignatureHeaders() {
        WebhookEndpointEntity endpoint = endpoint(1L, true, List.of("alert.triggered"), "   ");
        when(webhookEndpointService.listEntities()).thenReturn(List.of(endpoint));
        when(webhookDeliveryLogService.createPending(eq(1L), eq("alert.triggered"), any(), any()))
                .thenReturn(new WebhookDeliveryLogEntity());

        invokeTriggerAlertTriggered(webhookDispatcherService, alertsView());

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(1, capturedRequests.size()));
        ClientRequest request = capturedRequests.get(0);
        HttpHeaders headers = request.headers();

        assertEquals("alert.triggered", headers.getFirst("X-Webhook-Event"));
        assertFalse(headers.containsKey("X-Webhook-Timestamp"));
        assertFalse(headers.containsKey("X-Webhook-Signature"));
    }

    @Test
    void dispatchFailureShouldLogWarningAndNotThrow() {
        ObjectMapper objectMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        try {
            when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") { });
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
        Logger logger = (Logger) LoggerFactory.getLogger(WebhookDispatcherService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            WebhookDispatcherService service = new WebhookDispatcherService(
                    webhookEndpointService,
                    webhookDeliveryLogService,
                    webClientBuilder(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build())),
                    objectMapper,
                    true
            );

            assertDoesNotThrow(() -> invokeTriggerAlertTriggered(service, alertsView()));

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertTrue(appender.list.stream().anyMatch(event ->
                        event.getLevel() == Level.WARN && event.getFormattedMessage().contains("webhook_dispatch_failed")));
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void matchesEventShouldMatchEverythingForWildcard() throws Exception {
        Method method = WebhookDispatcherService.class.getDeclaredMethod("matchesEvent", WebhookEndpointEntity.class, String.class);
        method.setAccessible(true);

        WebhookEndpointEntity endpoint = endpoint(1L, true, List.of("*"), null);
        boolean matched = (boolean) method.invoke(webhookDispatcherService, endpoint, "any.event");

        assertTrue(matched);
    }

    @Test
    void triggerAlertTriggeredShouldCreatePendingWithSerializedPayload() {
        WebhookEndpointEntity endpoint = endpoint(1L, true, List.of("alert.triggered"), null);
        when(webhookEndpointService.listEntities()).thenReturn(List.of(endpoint));
        when(webhookDeliveryLogService.createPending(eq(1L), eq("alert.triggered"), any(), any()))
                .thenReturn(new WebhookDeliveryLogEntity());

        invokeTriggerAlertTriggered(webhookDispatcherService, alertsView());

        ArgumentCaptor<String> eventIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            verify(webhookDeliveryLogService).createPending(eq(1L), eq("alert.triggered"), eventIdCaptor.capture(), payloadCaptor.capture());
        });
        assertNotNull(eventIdCaptor.getValue());
        assertTrue(payloadCaptor.getValue().contains("\"eventType\":\"alert.triggered\""));
        assertTrue(payloadCaptor.getValue().contains("\"alerts\""));
    }

    @Test
    void dispatchShouldRetryBeforeFailing() {
        WebhookEndpointEntity endpoint = endpoint(1L, true, List.of("alert.triggered"), null);
        endpoint.setRetryMax(2);
        when(webhookEndpointService.listEntities()).thenReturn(List.of(endpoint));
        when(webhookDeliveryLogService.createPending(eq(1L), eq("alert.triggered"), any(), any()))
                .thenReturn(new WebhookDeliveryLogEntity());
        webhookDispatcherService = new WebhookDispatcherService(
                webhookEndpointService,
                webhookDeliveryLogService,
                webClientBuilder(request -> {
                    capturedRequests.add(request);
                    return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
                }),
                new ObjectMapper().registerModule(new JavaTimeModule()),
                true
        );

        invokeTriggerAlertTriggered(webhookDispatcherService, alertsView());

        await().atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            verify(webhookDeliveryLogService).markFailed(any(WebhookDeliveryLogEntity.class), eq(null), any(), eq(3));
            verify(webhookDeliveryLogService).recordAttempt(any(WebhookDeliveryLogEntity.class), eq(1));
            verify(webhookDeliveryLogService).recordAttempt(any(WebhookDeliveryLogEntity.class), eq(2));
            verify(webhookDeliveryLogService).recordAttempt(any(WebhookDeliveryLogEntity.class), eq(3));
        });
        assertEquals(3, capturedRequests.size());
    }

    private static WebClient.Builder webClientBuilder(ExchangeFunction exchangeFunction) {
        return WebClient.builder().exchangeFunction(exchangeFunction);
    }

    private static WebhookEndpointEntity endpoint(Long id, boolean enabled, List<String> eventTypes, String secret) {
        WebhookEndpointEntity entity = new WebhookEndpointEntity();
        entity.setId(id);
        entity.setName("endpoint-" + id);
        entity.setUrl("https://example.com/webhooks/" + id);
        entity.setEnabled(enabled);
        entity.setEventTypes(eventTypes);
        entity.setSecret(secret);
        entity.setRetryMax(3);
        entity.setTimeoutMs(1000);
        return entity;
    }

    private static Object alertsView() {
        try {
            Class<?> alertViewClass = Class.forName("io.gateway.oss.admin.web.alerts.AdminAlertsService$AlertView");
            Object alert = alertViewClass
                    .getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class,
                            String.class, Instant.class, Map.class)
                    .newInstance(
                            "alert-1",
                            "route_disabled",
                            "warning",
                            "active",
                            "Route is disabled",
                            "route-a",
                            Instant.now(),
                            Map.of("routeId", "route-a")
                    );

            Class<?> alertsViewClass = Class.forName("io.gateway.oss.admin.web.alerts.AdminAlertsService$AlertsView");
            return alertsViewClass
                    .getDeclaredConstructor(Instant.class, List.class, List.class)
                    .newInstance(Instant.now(), List.of(alert), List.of(alert));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void invokeTriggerAlertTriggered(WebhookDispatcherService service, Object alertsView) {
        try {
            Class<?> alertsViewClass = Class.forName("io.gateway.oss.admin.web.alerts.AdminAlertsService$AlertsView");
            service.getClass().getMethod("triggerAlertTriggered", alertsViewClass).invoke(service, alertsView);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
