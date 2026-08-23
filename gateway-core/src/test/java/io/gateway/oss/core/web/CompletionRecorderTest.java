package io.gateway.oss.core.web;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ModelPricing;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.TraceConfig;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.observability.RequestLogService;
import io.gateway.oss.core.observability.RequestLogService.RequestLogEntry;
import io.gateway.oss.core.observability.TraceRecord;
import io.gateway.oss.core.observability.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CompletionRecorderTest {

    @Mock
    private GatewayMetricsRecorder metricsRecorder;

    @Mock
    private RequestLogService requestLogService;

    @Mock
    private TraceStore traceStore;

    private CompletionRecorder recorder;
    private GatewayProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new GatewayProperties();

        TraceConfig traceConfig = new TraceConfig();
        traceConfig.setEnabled(true);
        traceConfig.setSampleRate(1.0);
        traceConfig.setMaxBodySize(4096);
        properties.setTracing(traceConfig);

        PricingConfig pricingConfig = new PricingConfig();
        ModelPricing defaultPricing = new ModelPricing();
        defaultPricing.setUnitPrice(new BigDecimal("0.001"));
        pricingConfig.setDefault(defaultPricing);

        ModelPricing modelPricing = new ModelPricing();
        modelPricing.setInputUnitPrice(new BigDecimal("0.002"));
        modelPricing.setOutputUnitPrice(new BigDecimal("0.003"));
        pricingConfig.setModels(Map.of("gpt-4o", modelPricing));
        properties.setPricing(pricingConfig);

        recorder = new CompletionRecorder(metricsRecorder, requestLogService, traceStore, objectMapper, properties);
    }

    @Test
    void recordRequestLog_recordsExpectedEntryWithCalculatedCost() {
        ResolvedRoute route = route();
        Instant start = Instant.now().minusMillis(50);

        recorder.recordRequestLog(exchange(), "cli***45", "gpt-4o", route, start,
                "stream", 10L, 5L, 15L, "req-1", "client-12345");

        ArgumentCaptor<RequestLogEntry> captor = ArgumentCaptor.forClass(RequestLogEntry.class);
        verify(requestLogService).record(captor.capture());

        RequestLogEntry entry = captor.getValue();
        assertEquals("req-1", entry.requestId());
        assertEquals("cli***45", entry.clientId());
        assertEquals("client-12345", entry.clientKey());
        assertEquals("gpt-4o", entry.model());
        assertEquals("openai", entry.provider());
        assertEquals("route-1", entry.routeId());
        assertEquals("chat", entry.scene());
        assertEquals(200, entry.status());
        assertEquals("stream", entry.streamMode());
        assertEquals(15L, entry.usageTokens());
        assertEquals(10L, entry.promptTokens());
        assertEquals(5L, entry.completionTokens());
        assertEquals(0.035d, entry.costUsd(), 0.000001d);
        assertNotNull(entry.timestamp());
        assertTrue(entry.latencyMs() >= 0);
        assertNull(entry.errorMessage());
    }

    @Test
    void recordSuccessObservability_recordsRedactedTrace() {
        MockServerWebExchange exchange = exchange();
        Instant start = Instant.now().minusMillis(100);
        Map<String, Object> request = Map.of(
                "email", "user@example.com",
                "note", "api_key=abcdefghijklmnop",
                "text", "Bearer ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        );
        String response = "contact user@example.com api_key=abcdefghijklmnop Bearer ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String redactedClient = CompletionRecorder.redact("client-12345");

        recorder.recordRequestLog(exchange, redactedClient, "gpt-4o", route(), start,
                "stream", 12L, 8L, 20L, "req-2", "client-12345");
        recorder.recordSuccessObservability(exchange, request, "client-12345", "gpt-4o", route(), start,
                "stream", 12L, 8L, 20L, "req-2", response);

        verify(metricsRecorder).recordSuccess(exchange, 200);

        ArgumentCaptor<RequestLogEntry> requestLogCaptor = ArgumentCaptor.forClass(RequestLogEntry.class);
        verify(requestLogService).record(requestLogCaptor.capture());
        RequestLogEntry requestLogEntry = requestLogCaptor.getValue();
        assertEquals("cli***45", requestLogEntry.clientId());
        assertEquals("client-12345", requestLogEntry.clientKey());
        assertEquals(20L, requestLogEntry.usageTokens());

        ArgumentCaptor<TraceRecord> traceCaptor = ArgumentCaptor.forClass(TraceRecord.class);
        verify(traceStore).save(traceCaptor.capture());
        TraceRecord traceRecord = traceCaptor.getValue();
        assertEquals("req-2", traceRecord.requestId());
        assertEquals("cli***45", traceRecord.clientId());
        assertEquals("openai", traceRecord.provider());
        assertEquals("route-1", traceRecord.routeId());
        assertEquals("chat", traceRecord.scene());
        assertEquals(200, traceRecord.status());
        assertEquals("stream", traceRecord.streamMode());
        assertNull(traceRecord.errorMessage());
        assertNull(traceRecord.requestBody());
        assertNull(traceRecord.responseBody());
        assertTrue(traceRecord.latencyMs() >= 0);
    }

    @Test
    void recordSuccessArtifacts_recordsRequestLogAndTraceTogether() {
        MockServerWebExchange exchange = exchange();
        Instant start = Instant.now().minusMillis(60);
        String redactedClient = CompletionRecorder.redact("client-12345");

        recorder.recordSuccessArtifacts(exchange, Map.of("prompt", "hello"), "client-12345", redactedClient,
                "gpt-4o", route(), start, "non-streaming", 9L, 6L, 15L, "req-joined-1", Map.of("id", "resp-1"), 0.123d);

        ArgumentCaptor<RequestLogEntry> requestLogCaptor = ArgumentCaptor.forClass(RequestLogEntry.class);
        verify(requestLogService).record(requestLogCaptor.capture());
        RequestLogEntry requestLogEntry = requestLogCaptor.getValue();
        assertEquals("req-joined-1", requestLogEntry.requestId());
        assertEquals(redactedClient, requestLogEntry.clientId());
        assertEquals("client-12345", requestLogEntry.clientKey());
        assertEquals(15L, requestLogEntry.usageTokens());
        assertEquals(0.123d, requestLogEntry.costUsd());

        ArgumentCaptor<TraceRecord> traceCaptor = ArgumentCaptor.forClass(TraceRecord.class);
        verify(traceStore).save(traceCaptor.capture());
        TraceRecord traceRecord = traceCaptor.getValue();
        assertEquals("req-joined-1", traceRecord.requestId());
        assertEquals(redactedClient, traceRecord.clientId());
        assertEquals(200, traceRecord.status());

        verify(metricsRecorder).recordSuccess(exchange, 200);
    }

    @Test
    void recordFailureObservability_recordsFailureMetricsRequestLogAndTrace() {
        MockServerWebExchange exchange = exchange();
        Instant start = Instant.now().minusMillis(80);
        Map<String, Object> responseSummary = Map.of("token", "Bearer ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        String redactedClient = CompletionRecorder.redact("client-12345");

        recorder.recordRequestFailure(redactedClient, "client-12345", "gpt-4o", route(), start,
                "non-stream", "req-3", 429, "rate_limited");
        recorder.recordFailureObservability(exchange, Map.of("prompt", "hello"), "client-12345", "gpt-4o", route(), start,
                "non-stream", "req-3", 429, "contact support@example.com", responseSummary);

        verify(metricsRecorder).recordFailure(exchange, 429);

        ArgumentCaptor<RequestLogEntry> requestLogCaptor = ArgumentCaptor.forClass(RequestLogEntry.class);
        verify(requestLogService).record(requestLogCaptor.capture());
        RequestLogEntry requestLogEntry = requestLogCaptor.getValue();
        assertEquals("req-3", requestLogEntry.requestId());
        assertEquals("cli***45", requestLogEntry.clientId());
        assertEquals("client-12345", requestLogEntry.clientKey());
        assertEquals(429, requestLogEntry.status());
        assertEquals("rate_limited", requestLogEntry.errorMessage());
        assertNull(requestLogEntry.usageTokens());

        ArgumentCaptor<TraceRecord> traceCaptor = ArgumentCaptor.forClass(TraceRecord.class);
        verify(traceStore).save(traceCaptor.capture());
        TraceRecord traceRecord = traceCaptor.getValue();
        assertEquals(429, traceRecord.status());
        assertEquals("contact support@example.com", traceRecord.errorMessage());
        assertNotNull(traceRecord.responseBody());
        assertTrue(traceRecord.responseBody().contains("[REDACTED]"));
        assertTrue(!traceRecord.responseBody().contains("Bearer ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
    }

    private MockServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/chat/completions").build());
        exchange.getAttributes().put(RequestIdFilter.REQUEST_ID_ATTR, "req-attr");
        return exchange;
    }

    private ResolvedRoute route() {
        return new ResolvedRoute(
                "gpt-4o",
                "route-1",
                "chat",
                "openai",
                "openai",
                "gpt-4o",
                "https://example.com",
                "provider-secret",
                Duration.ofSeconds(30),
                2,
                List.of()
        );
    }
}
