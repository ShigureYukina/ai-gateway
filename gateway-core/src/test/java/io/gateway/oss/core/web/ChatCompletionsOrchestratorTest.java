package io.gateway.oss.core.web;

import io.gateway.oss.core.config.Backend;
import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.ClientDefaults;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.error.ErrorCode;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.limit.ClientRateLimiter;
import io.gateway.oss.core.limit.ConcurrentRequestLimiter;
import io.gateway.oss.core.contract.AggregateMetricRecorder;
import io.gateway.oss.core.contract.BudgetService;
import io.gateway.oss.core.contract.QuotaService;
import io.gateway.oss.core.contract.TpmService;
import io.gateway.oss.core.routing.ModelRouteResolver;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.security.ClientAuthService;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.upstream.UpstreamChatClient;
import io.gateway.oss.core.util.BatchFlusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatCompletionsOrchestratorTest {

    @Mock private ClientAuthService clientAuthService;
    @Mock private ModelRouteResolver routeResolver;
    @Mock private ClientRateLimiter rateLimiter;
    @Mock private TpmService tpmService;
    @Mock private QuotaService quotaService;
    @Mock private BudgetService budgetService;
    @Mock private AggregateMetricRecorder aggregateMetricRecorder;
    @Mock private UpstreamChatClient upstreamClient;
    @Mock private OperationalGateService operationalGateService;
    @Mock private ConcurrentRequestLimiter concurrentRequestLimiter;
    @Mock private CompletionRecorder completionRecorder;
    @Mock private ServerWebExchange exchange;
    @Mock private ServerHttpResponse serverHttpResponse;

    private GatewayProperties properties;
    private BatchFlusher batchFlusher;
    private ChatCompletionsOrchestrator orchestrator;

    private static final String CLIENT_ID = "test-client-12345";
    private static final String AUTH_HEADER = "Bearer test-token";
    private static final ClientPrincipal PRINCIPAL = new ClientPrincipal(CLIENT_ID, null, "user");
    private static final ResolvedRoute ROUTE = new ResolvedRoute(
            "gpt-4o-mini", "route-1", "default", "openai", "openai-compatible",
            "gpt-4o-mini", "http://localhost:8080", List.of("key1"), "key1",
            Duration.ofSeconds(30), 3, List.of(), 1
    );

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.getSharedState().setBackend(Backend.IN_MEMORY);
        batchFlusher = new BatchFlusher(new GatewayProperties());
        batchFlusher.setSynchronous(true);

        orchestrator = new ChatCompletionsOrchestrator(
                clientAuthService, routeResolver, rateLimiter,
                upstreamClient, properties,
                operationalGateService, concurrentRequestLimiter,
                completionRecorder, batchFlusher,
                Schedulers.immediate(),
                new AdminRuntimeServices(quotaService, budgetService, tpmService, aggregateMetricRecorder)
        );

        lenient().when(exchange.getResponse()).thenReturn(serverHttpResponse);
        lenient().when(serverHttpResponse.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        lenient().when(exchange.getAttributeOrDefault(eq(RequestIdFilter.REQUEST_ID_ATTR), anyString()))
                .thenReturn("req-test-123");
    }

    @Test
    void successfulNonStreamRequest_recordsOriginalRequestIntoTraceRecorder() {
        stubHappyPath();
        Map<String, Object> upstreamResponse = Map.of("id", "chatcmpl-1", "object", "chat.completion",
                "usage", Map.of("total_tokens", 50, "prompt_tokens", 30, "completion_tokens", 20));
        when(upstreamClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(upstreamResponse));
        when(quotaService.resolveUsageTokensForResponse(any(), any())).thenReturn(50L);
        doNothing().when(quotaService).recordUsage(any(), any(), any(), any());

        ChatCompletionsRequest request = nonStreamRequest();
        Mono<ResponseEntity<?>> result = orchestrator.orchestrate(request, AUTH_HEADER, exchange);

        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        verify(completionRecorder).calculateCostUsd("gpt-4o-mini", 30L, 20L);
        ArgumentCaptor<ChatCompletionsRequest> recordedRequestCaptor = ArgumentCaptor.forClass(ChatCompletionsRequest.class);
        verify(completionRecorder).recordSuccessArtifacts(eq(exchange), recordedRequestCaptor.capture(), eq(CLIENT_ID),
                eq(CompletionRecorder.redact(CLIENT_ID)), eq("gpt-4o-mini"), eq(ROUTE), any(), eq("non-streaming"),
                eq(30L), eq(20L), eq(50L), eq("req-test-123"), eq(upstreamResponse), eq(0.0d));
        ChatCompletionsRequest recordedRequest = recordedRequestCaptor.getValue();
        assertNotSame(request, recordedRequest);
        assertEquals(request.model(), recordedRequest.model());
        assertEquals(request.messages(), recordedRequest.messages());
        assertEquals(request.stream(), recordedRequest.stream());
        assertEquals(request.temperature(), recordedRequest.temperature());
        assertEquals(request.maxTokens(), recordedRequest.maxTokens());
        assertEquals("req-test-123", recordedRequest.extras().get(ChatCompletionsRequest.GATEWAY_REQUEST_ID_EXTRA));

    }

    @Test
    void successfulNonStreamRequest_whenSuccessArtifactsThrows_stillRecordsAggregate() {
        stubHappyPath();
        Map<String, Object> upstreamResponse = Map.of("id", "chatcmpl-1", "object", "chat.completion",
                "usage", Map.of("total_tokens", 50, "prompt_tokens", 30, "completion_tokens", 20));
        when(upstreamClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(upstreamResponse));
        when(quotaService.resolveUsageTokensForResponse(any(), any())).thenReturn(50L);
        doNothing().when(quotaService).recordUsage(any(), any(), any(), any());
        doThrow(new RuntimeException("boom")).when(completionRecorder).recordSuccessArtifacts(
                any(), any(), anyString(), anyString(), anyString(), any(), any(), anyString(),
                anyLong(), anyLong(), anyLong(), anyString(), any(), any());

        StepVerifier.create(orchestrator.orchestrate(nonStreamRequest(), AUTH_HEADER, exchange))
                .expectNextCount(1)
                .verifyComplete();

        verify(aggregateMetricRecorder).recordSuccess(
                eq("req-test-123"),
                eq(PRINCIPAL),
                eq(ROUTE),
                eq("gpt-4o-mini"),
                eq(50L),
                eq(0.0d),
                any());
    }

    @Test
    void applyClientDefaults_preservesExtendedRequestFields() {
        stubHappyPath();
        ClientPrincipal principalWithDefaults = new ClientPrincipal(CLIENT_ID, clientConfigWithDefaults(), "user");
        when(clientAuthService.authenticate(AUTH_HEADER)).thenReturn(principalWithDefaults);
        when(upstreamClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "chatcmpl-1", "usage", Map.of("total_tokens", 10))));
        when(quotaService.resolveUsageTokensForResponse(any(), any())).thenReturn(10L);
        doNothing().when(quotaService).recordUsage(any(), any(), any(), any());

        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello", null)),
                false,
                null,
                null,
                List.of(Map.of("type", "function", "function", Map.of("name", "lookup_weather"))),
                Map.of("type", "function", "function", Map.of("name", "lookup_weather")),
                Map.of("type", "json_object"),
                Map.of("mock_scenario", "provider-error", "custom_flag", true)
        );

        StepVerifier.create(orchestrator.orchestrate(request, AUTH_HEADER, exchange))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<ChatCompletionsRequest> requestCaptor = ArgumentCaptor.forClass(ChatCompletionsRequest.class);
        verify(upstreamClient).completeWithFallback(requestCaptor.capture(), eq(ROUTE), any());
        ChatCompletionsRequest forwarded = requestCaptor.getValue();
        assertEquals(0.9, forwarded.temperature());
        assertEquals(512, forwarded.maxTokens());
        assertEquals(request.tools(), forwarded.tools());
        assertEquals(request.toolChoice(), forwarded.toolChoice());
        assertEquals(request.responseFormat(), forwarded.responseFormat());
        assertEquals("provider-error", forwarded.extras().get("mock_scenario"));
        assertEquals(true, forwarded.extras().get("custom_flag"));
    }

    @Test
    void completionRecorder_capturesTraceMetadataAndRequestBody() {
        var metricsRecorder = mock(io.gateway.oss.core.observability.GatewayMetricsRecorder.class);
        var requestLogService = mock(io.gateway.oss.core.observability.RequestLogService.class);
        var traceStore = mock(io.gateway.oss.core.observability.TraceStore.class);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        GatewayProperties props = new GatewayProperties();
        props.getTracing().setEnabled(true);
        CompletionRecorder recorder = new CompletionRecorder(
                metricsRecorder,
                requestLogService,
                traceStore,
                objectMapper,
                props
        );
        MockServerWebExchange mockExchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions").build());
        mockExchange.getAttributes().put(RequestIdFilter.REQUEST_ID_ATTR, "req-trace-1");
        ChatCompletionsRequest request = new ChatCompletionsRequest("gpt-4o-mini",
                List.of(new ChatMessage("user", "email user@example.com api_key=sk-1234567890abcdef1234567890 token=abc1234567890123456", null)),
                false, 0.7, 128);
        Map<String, Object> response = Map.of("id", "chatcmpl-1", "usage", Map.of("total_tokens", 9));

        recorder.recordRequestLog(mockExchange, CompletionRecorder.redact(CLIENT_ID), "gpt-4o-mini", ROUTE,
                java.time.Instant.now().minusMillis(25), "non-streaming", 5L, 4L, 9L, "req-trace-1", CLIENT_ID);
        recorder.recordSuccessObservability(mockExchange, request, CLIENT_ID, "gpt-4o-mini", ROUTE,
                java.time.Instant.now().minusMillis(25), "non-streaming", 5L, 4L, 9L, "req-trace-1", response);

        var captor = org.mockito.ArgumentCaptor.forClass(io.gateway.oss.core.observability.TraceRecord.class);
        verify(traceStore).save(captor.capture());
        var trace = captor.getValue();
        assertEquals("req-trace-1", trace.requestId());
        assertEquals("tes***45", trace.clientId());
        assertEquals("gpt-4o-mini", trace.model());
        assertEquals("openai", trace.provider());
        assertEquals("route-1", trace.routeId());
        assertEquals("default", trace.scene());
        assertEquals(200, trace.status());
        assertEquals("non-streaming", trace.streamMode());
        assertNotNull(trace.latencyMs());
        assertTrue(trace.latencyMs() >= 0);
        assertNull(trace.requestBody());
        assertNull(trace.responseBody());
        assertNull(trace.errorMessage());
    }

    private ChatCompletionsRequest nonStreamRequest() {
        return new ChatCompletionsRequest("gpt-4o-mini",
                List.of(new ChatMessage("user", "hello", null)),
                false, 0.7, 128);
    }

    private ChatCompletionsRequest streamRequest() {
        return new ChatCompletionsRequest("gpt-4o-mini",
                List.of(new ChatMessage("user", "hello", null)),
                true, 0.7, 128);
    }

    private void stubHappyPath() {
        doNothing().when(operationalGateService).preCheck(any());
        when(clientAuthService.authenticate(AUTH_HEADER)).thenReturn(PRINCIPAL);
        doNothing().when(clientAuthService).authorizeModel(any(), anyString());
        doNothing().when(clientAuthService).validateRequestCapabilities(any(), anyBoolean(), any());
        doNothing().when(rateLimiter).check(anyString());
        doNothing().when(quotaService).checkDailyQuota(any(), any());
        doNothing().when(quotaService).checkMonthlyQuota(any(), any());
        doNothing().when(budgetService).checkDailyBudget(any(), any());
        doNothing().when(budgetService).checkMonthlyBudget(any(), any());
        when(tpmService.reserveEstimatedTokens(any(), any(), any())).thenReturn(100L);
        when(routeResolver.resolve(anyString(), any())).thenReturn(ROUTE);
        doNothing().when(clientAuthService).authorizeScene(any(), anyString());
    }

    private ClientConfig clientConfigWithDefaults() {
        ClientConfig config = new ClientConfig();
        ClientDefaults defaults = new ClientDefaults();
        defaults.setTemperature(0.9);
        defaults.setMaxTokens(512);
        config.setDefaults(defaults);
        return config;
    }

    // ===== Auth failure returns 401 =====

    @Test
    void authFailure_returns401() {
        doNothing().when(operationalGateService).preCheck(any());
        when(clientAuthService.authenticate(AUTH_HEADER))
                .thenThrow(ErrorCode.UNAUTHORIZED.exception("Invalid credentials"));

        Mono<ResponseEntity<?>> result = orchestrator.orchestrate(nonStreamRequest(), AUTH_HEADER, exchange);

        StepVerifier.create(result)
                .expectErrorMatches(ex -> {
                    GatewayException gex = (GatewayException) ex;
                    return gex.getStatus() == HttpStatus.UNAUTHORIZED
                            && "unauthorized".equals(gex.getCode());
                })
                .verify();

        verify(completionRecorder).recordRequestFailure(eq(CompletionRecorder.redact(null)), isNull(), eq("gpt-4o-mini"), isNull(),
                any(), eq("pre-route"), eq("req-test-123"), eq(401), eq("unauthorized"));
        verify(completionRecorder).recordPreRouteFailureObservability(eq(exchange), any(), isNull(), eq("gpt-4o-mini"), any(),
                eq("req-test-123"), eq(401), contains("Invalid credentials"));
    }

    // ===== Rate limit exceeded returns 429 =====

    @Test
    void rateLimitExceeded_returns429() {
        doNothing().when(operationalGateService).preCheck(any());
        when(clientAuthService.authenticate(AUTH_HEADER)).thenReturn(PRINCIPAL);
        doNothing().when(clientAuthService).authorizeModel(any(), anyString());
        doNothing().when(clientAuthService).validateRequestCapabilities(any(), anyBoolean(), any());
        // Phase 1 (event loop) now includes route resolution before Phase 2 rate-limit check
        when(routeResolver.resolve(anyString(), any())).thenReturn(ROUTE);
        doNothing().when(clientAuthService).authorizeScene(any(), anyString());
        doThrow(ErrorCode.RATE_LIMITED.exception("Rate limit exceeded"))
                .when(rateLimiter).check(CLIENT_ID);

        Mono<ResponseEntity<?>> result = orchestrator.orchestrate(nonStreamRequest(), AUTH_HEADER, exchange);

        StepVerifier.create(result)
                .expectErrorMatches(ex -> {
                    GatewayException gex = (GatewayException) ex;
                    return gex.getStatus() == HttpStatus.TOO_MANY_REQUESTS
                            && "rate_limited".equals(gex.getCode());
                })
                .verify();

        verify(upstreamClient, never()).completeWithFallback(any(), any(), any());
        verify(upstreamClient, never()).streamWithFallback(any(), any(), any());
    }

    // ===== Successful non-stream request passes through to upstream =====

    @Test
    void successfulNonStreamRequest_passesThroughToUpstream() {
        stubHappyPath();
        Map<String, Object> upstreamResponse = Map.of("id", "chatcmpl-1", "object", "chat.completion",
                "usage", Map.of("total_tokens", 50, "prompt_tokens", 30, "completion_tokens", 20));
        when(upstreamClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(upstreamResponse));
        when(quotaService.resolveUsageTokensForResponse(any(), any())).thenReturn(50L);
        doNothing().when(quotaService).recordUsage(any(), any(), any(), any());

        Mono<ResponseEntity<?>> result = orchestrator.orchestrate(nonStreamRequest(), AUTH_HEADER, exchange);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    // Body should contain the upstream response
                    assertNotNull(response.getBody());
                })
                .verifyComplete();

        verify(upstreamClient).completeWithFallback(any(), eq(ROUTE), any());
        verify(tpmService).reconcile(eq(CLIENT_ID), eq(100L), eq(50L), any());
    }

    // ===== Successful stream request passes through =====

    @Test
    void successfulStreamRequest_passesThrough() {
        stubHappyPath();
        when(upstreamClient.streamWithFallback(any(), any(), any()))
                .thenReturn(Flux.just("data: {\"id\":\"x\"}\n\n", "data: [DONE]\n\n"));

        Mono<ResponseEntity<?>> result = orchestrator.orchestrate(streamRequest(), AUTH_HEADER, exchange);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(MediaType.TEXT_EVENT_STREAM, response.getHeaders().getContentType());
                })
                .verifyComplete();

        verify(upstreamClient).streamWithFallback(any(), eq(ROUTE), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamRequest_complete_releasesConcurrency_andReconcilesTpm_once() {
        stubHappyPath();
        when(upstreamClient.streamWithFallback(any(), any(), any()))
                .thenReturn(Flux.just(
                        "data: {\"id\":\"x\"}\n\n",
                        "data: {\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":20,\"total_tokens\":50}}\n\n",
                        "data: [DONE]\n\n"
                ));
        when(quotaService.resolveUsageTokensForStreaming(eq(50L), any())).thenReturn(50L);

        ResponseEntity<?> response = orchestrator.orchestrate(streamRequest(), AUTH_HEADER, exchange).block();

        assertNotNull(response);
        Flux<String> body = (Flux<String>) response.getBody();
        assertNotNull(body);

        StepVerifier.create(body)
                .expectNext("data: {\"id\":\"x\"}\n\n")
                .expectNext("data: {\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":20,\"total_tokens\":50}}\n\n")
                .expectNext("data: [DONE]\n\n")
                .verifyComplete();

        verify(concurrentRequestLimiter).acquire(CLIENT_ID);
        verify(concurrentRequestLimiter).release(CLIENT_ID);
        verify(tpmService).reconcile(eq(CLIENT_ID), eq(100L), eq(50L), any());
        verify(tpmService, never()).release(eq(CLIENT_ID), anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamRequest_error_releasesConcurrency_andTpmRelease_once() {
        stubHappyPath();
        when(upstreamClient.streamWithFallback(any(), any(), any()))
                .thenReturn(Flux.error(new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Upstream error")));

        ResponseEntity<?> response = orchestrator.orchestrate(streamRequest(), AUTH_HEADER, exchange).block();

        assertNotNull(response);
        Flux<String> body = (Flux<String>) response.getBody();
        assertNotNull(body);

        StepVerifier.create(body)
                .expectErrorMatches(ex -> ex instanceof GatewayException gex
                        && gex.getStatus() == HttpStatus.BAD_GATEWAY
                        && "upstream_error".equals(gex.getCode()))
                .verify();

        verify(concurrentRequestLimiter).acquire(CLIENT_ID);
        verify(concurrentRequestLimiter).release(CLIENT_ID);
        verify(tpmService).release(eq(CLIENT_ID), eq(100L), any());
        verify(tpmService, never()).reconcile(anyString(), anyLong(), anyLong(), any());
        verify(quotaService, never()).recordStreamingUsageOnSuccess(any(), any(), any(), any());
        verify(budgetService, never()).recordCostOnSuccess(any(), any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamRequest_postFirstBusinessChunkThenInterrupted_treatedAsFailure_notSuccess() {
        stubHappyPath();
        when(upstreamClient.streamWithFallback(any(), any(), any()))
                .thenReturn(Flux.just("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n")
                        .concatWith(Flux.error(new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Mid-stream failure"))));

        ResponseEntity<?> response = orchestrator.orchestrate(streamRequest(), AUTH_HEADER, exchange).block();

        assertNotNull(response);
        Flux<String> body = (Flux<String>) response.getBody();
        assertNotNull(body);

        StepVerifier.create(body)
                .expectNext("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n")
                .verifyComplete();

        verify(tpmService).release(eq(CLIENT_ID), eq(100L), any());
        verify(tpmService, never()).reconcile(anyString(), anyLong(), anyLong(), any());
        verify(concurrentRequestLimiter).release(CLIENT_ID);
        verify(quotaService, never()).recordStreamingUsageOnSuccess(any(), any(), any(), any());
        verify(budgetService, never()).recordCostOnSuccess(any(), any(), any(), anyLong(), anyLong(), any());
        verify(completionRecorder).recordRequestFailure(eq(CompletionRecorder.redact(CLIENT_ID)), eq(CLIENT_ID), eq("gpt-4o-mini"), eq(ROUTE),
                any(), eq("streaming"), eq("req-test-123"), eq(502), eq("upstream_error"));
        verify(completionRecorder).recordFailureObservability(eq(exchange), any(), eq(CLIENT_ID), eq("gpt-4o-mini"), eq(ROUTE),
                any(), eq("streaming"), eq("req-test-123"), eq(502), eq("Mid-stream failure"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamRequest_cancel_releasesConcurrency_andTpmRelease_once() {
        stubHappyPath();
        when(upstreamClient.streamWithFallback(any(), any(), any()))
                .thenReturn(Flux.just("data: {\"id\":\"x\"}\n\n").concatWith(Flux.never()));

        ResponseEntity<?> response = orchestrator.orchestrate(streamRequest(), AUTH_HEADER, exchange).block();

        assertNotNull(response);
        Flux<String> body = (Flux<String>) response.getBody();
        assertNotNull(body);

        StepVerifier.create(body)
                .expectNext("data: {\"id\":\"x\"}\n\n")
                .thenCancel()
                .verify();

        verify(concurrentRequestLimiter).acquire(CLIENT_ID);
        verify(concurrentRequestLimiter).release(CLIENT_ID);
        verify(tpmService).release(eq(CLIENT_ID), eq(100L), any());
        verify(tpmService, never()).reconcile(anyString(), anyLong(), anyLong(), any());
        verify(quotaService, never()).recordStreamingUsageOnSuccess(any(), any(), any(), any());
        verify(budgetService, never()).recordCostOnSuccess(any(), any(), any(), anyLong(), anyLong(), any());
    }

    // ===== Concurrent request limiter acquire/release lifecycle =====

    @Test
    void concurrentRequestLimiter_acquireAndReleaseLifecycle() {
        stubHappyPath();
        when(upstreamClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "ok")));
        when(quotaService.resolveUsageTokensForResponse(any(), any())).thenReturn(10L);
        doNothing().when(quotaService).recordUsage(any(), any(), any(), any());

        Mono<ResponseEntity<?>> result = orchestrator.orchestrate(nonStreamRequest(), AUTH_HEADER, exchange);

        StepVerifier.create(result)
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(concurrentRequestLimiter).acquire(CLIENT_ID);
        verify(concurrentRequestLimiter).release(CLIENT_ID);
    }

    @Test
    void concurrentRequestLimiter_releasesOnError() {
        stubHappyPath();
        when(upstreamClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.error(new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Upstream error")));

        Mono<ResponseEntity<?>> result = orchestrator.orchestrate(nonStreamRequest(), AUTH_HEADER, exchange);

        StepVerifier.create(result)
                .expectErrorMatches(ex -> ex instanceof GatewayException)
                .verify();

        verify(concurrentRequestLimiter).acquire(CLIENT_ID);
        verify(concurrentRequestLimiter).release(CLIENT_ID);
    }

    @Test
    void concurrentRequestLimiter_acquireRejected_returns429() {
        stubHappyPath();
        AtomicInteger upstreamSubscriptionCount = new AtomicInteger(0);
        doThrow(ErrorCode.CONCURRENT_LIMIT_EXCEEDED.exception("Concurrent limit exceeded"))
                .when(concurrentRequestLimiter).acquire(CLIENT_ID);
        when(upstreamClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.defer(() -> {
                    upstreamSubscriptionCount.incrementAndGet();
                    return Mono.just(Map.of("id", "should-not-be-emitted"));
                }));

        Mono<ResponseEntity<?>> result = orchestrator.orchestrate(nonStreamRequest(), AUTH_HEADER, exchange);

        StepVerifier.create(result)
                .expectErrorMatches(ex -> {
                    GatewayException gex = (GatewayException) ex;
                    return gex.getStatus() == HttpStatus.TOO_MANY_REQUESTS
                            && "concurrent_limit_exceeded".equals(gex.getCode());
                })
                .verify();

        verify(upstreamClient).completeWithFallback(any(), any(), any());
        assertEquals(0, upstreamSubscriptionCount.get());
        verify(upstreamClient, never()).streamWithFallback(any(), any(), any());
        verify(concurrentRequestLimiter, never()).release(CLIENT_ID);
        verify(tpmService).release(eq(CLIENT_ID), eq(100L), any());
        verify(tpmService, never()).reconcile(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void optionalAdminServicesAbsent_stillAllowsCoreOnlyRequestFlow() {
        orchestrator = new ChatCompletionsOrchestrator(
                clientAuthService, routeResolver, rateLimiter,
                upstreamClient, properties,
                operationalGateService, concurrentRequestLimiter,
                completionRecorder, batchFlusher,
                Schedulers.immediate(),
                AdminRuntimeServices.none()
        );
        doNothing().when(operationalGateService).preCheck(any());
        when(clientAuthService.authenticate(AUTH_HEADER)).thenReturn(PRINCIPAL);
        doNothing().when(clientAuthService).authorizeModel(any(), anyString());
        doNothing().when(clientAuthService).validateRequestCapabilities(any(), anyBoolean(), any());
        doNothing().when(rateLimiter).check(anyString());
        when(routeResolver.resolve(anyString(), any())).thenReturn(ROUTE);
        doNothing().when(clientAuthService).authorizeScene(any(), anyString());
        when(upstreamClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "chatcmpl-1", "usage", Map.of("total_tokens", 12))));

        StepVerifier.create(orchestrator.orchestrate(nonStreamRequest(), AUTH_HEADER, exchange))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();

        verify(upstreamClient).completeWithFallback(any(), eq(ROUTE), any());
        verifyNoInteractions(quotaService, budgetService, tpmService);
    }
}
