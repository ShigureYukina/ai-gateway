package io.gateway.oss.admin.web;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.limit.ClientRateLimiter;
import io.gateway.oss.admin.quota.ClientBudgetService;
import io.gateway.oss.admin.quota.ClientCostStore;
import io.gateway.oss.admin.quota.ClientQuotaService;
import io.gateway.oss.admin.quota.ClientUsageStore;
import io.gateway.oss.core.upstream.UpstreamChatClient;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.util.BatchFlusher;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import reactor.test.StepVerifier;

import io.gateway.oss.core.contract.routing.ResolvedRoute;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "gateway.shared-state.backend=in_memory",
        "gateway.auth.enabled=true",
        "gateway.auth.jwt.secret=super-secret-key-that-is-at-least-32-chars",
        "gateway.auth.users.admin.password=admin123",
        "gateway.auth.users.admin.client-id=demo-client-key",
        "gateway.auth.users.admin.role=admin",
        "gateway.clients.demo-client-key.enabled=true",
        "gateway.clients.demo-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.demo-client-key.allowed-scenes[0]=default-chat",
        "gateway.clients.demo-client-key.defaults.scene=default-chat",
        "gateway.clients.demo-client-key.defaults.temperature=0.7",
        "gateway.clients.demo-client-key.defaults.max-tokens=256",
        "gateway.clients.demo-client-key.capabilities.streaming=true",
        "gateway.clients.demo-client-key.limits.max-tokens=512",
        "gateway.clients.demo-client-key.limits.daily-tokens=100000",
        "gateway.clients.demo-client-key.limits.monthly-tokens=3000000",
        "gateway.clients.demo-client-key.limits.daily-cost=5.0",
        "gateway.clients.demo-client-key.limits.monthly-cost=100.0",
        "gateway.clients.demo-client-key.limits.tokens-per-minute=10000",
        "gateway.routes.openai-primary.provider=openai",
        "gateway.routes.openai-primary.upstream-model=gpt-4o-mini",
        "gateway.routes.openai-backup.provider=openai",
        "gateway.routes.openai-backup.upstream-model=gpt-4o-mini-backup",
        "gateway.routes.gpt-4o-mini.scene=default-chat",
        "gateway.providers.openai.base-url=http://localhost:18080",
        "gateway.providers.openai.api-key=upstream-demo-key",
        "gateway.scenes.default-chat.primary-route=openai-primary",
        "gateway.scenes.default-chat.fallback-routes[0]=openai-backup",
        "gateway.scenes.premium.primary-route=openai-primary",
        "gateway.scenes.premium.fallback-routes[0]=openai-backup",
        "gateway.limit.requests-per-window=2",
        "gateway.limit.window=5m",
        "gateway.clients.unknown-model-client-key.enabled=true",
        "gateway.clients.unknown-model-client-key.allowed-models[0]=gpt-4o-missing",
        "gateway.clients.scene-client-key.enabled=true",
        "gateway.clients.scene-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.scene-client-key.allowed-scenes[0]=premium",
        "gateway.clients.scene-client-key.model-scenes.gpt-4o-mini=premium",
        "gateway.clients.scene-client-key.defaults.temperature=0.4",
        "gateway.clients.scene-client-key.defaults.max-tokens=64",
        "gateway.clients.no-stream-client-key.enabled=true",
        "gateway.clients.no-stream-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.no-stream-client-key.capabilities.streaming=false",
        "gateway.clients.default-over-limit-client-key.enabled=true",
        "gateway.clients.default-over-limit-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.default-over-limit-client-key.defaults.max-tokens=999",
        "gateway.clients.default-over-limit-client-key.limits.max-tokens=128",
        "gateway.clients.daily-quota-client-key.enabled=true",
        "gateway.clients.daily-quota-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.daily-quota-client-key.limits.daily-tokens=1",
        "gateway.clients.daily-budget-client-key.enabled=true",
        "gateway.clients.daily-budget-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.daily-budget-client-key.limits.daily-cost=0.0002",
        "gateway.clients.monthly-quota-client-key.enabled=true",
        "gateway.clients.monthly-quota-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.monthly-quota-client-key.limits.monthly-tokens=1",
        "gateway.clients.monthly-budget-client-key.enabled=true",
        "gateway.clients.monthly-budget-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.monthly-budget-client-key.limits.monthly-cost=0.0002",
        "gateway.clients.tpm-client-key.enabled=true",
        "gateway.clients.tpm-client-key.allowed-models[0]=gpt-4o-mini",
        "gateway.clients.tpm-client-key.limits.tokens-per-minute=200",
        "gateway.pricing.default.unit-price=0.0001"
})
class ChatCompletionsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private UpstreamChatClient upstreamChatClient;

    @SpyBean
    private ClientQuotaService quotaService;

    @SpyBean
    private ClientBudgetService budgetService;

    @Autowired
    private ClientRateLimiter rateLimiter;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ClientUsageStore usageStore;

    @Autowired
    private ClientCostStore costStore;

    @Autowired
    private BatchFlusher batchFlusher;

    @BeforeEach
    void setUp() {
        // 全量套件并行负载下 5s 默认响应超时偶发不够，统一放宽到 30s
        webTestClient = webTestClient.mutate().responseTimeout(java.time.Duration.ofSeconds(30)).build();

        reset(upstreamChatClient);
        reset(quotaService);
        reset(budgetService);
        rateLimiter.reset();
        batchFlusher.setSynchronous(true);
    }

    @Test
    void shouldRejectMissingAuth() {
        webTestClient.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unauthorized")
                .jsonPath("$.requestId").exists();
    }

    @Test
    void shouldReturnJsonForNonStreaming() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any())).thenReturn(Mono.just(Map.of(
                "id", "chatcmpl_1",
                "object", "chat.completion",
                "choices", new Object[]{Map.of("message", Map.of("content", "hello from upstream"))}
        )));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_1")
                .jsonPath("$.choices[0].message.content").isEqualTo("hello from upstream");

        verify(quotaService, times(1)).recordUsage(any(), any(), any(), any());
        verify(budgetService, times(1)).recordCostOnSuccess(any(), any(), any(), eq(128L), eq(0L), any());
    }

    @Test
    void shouldRejectForbiddenModel() {
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-secret",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("forbidden_model");

        verify(upstreamChatClient, never()).completeWithFallback(any(), any(), any());
    }

    @Test
    void shouldRejectUnknownModelWithoutCallingUpstream() {
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer unknown-model-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-missing",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("unknown_model");

        verify(upstreamChatClient, never()).completeWithFallback(any(), any(), any());
    }

    @Test
    void shouldReturnSseForStreaming() {
        when(upstreamChatClient.streamWithFallback(any(), any(), any())).thenReturn(Flux.just("data: {\"id\":\"x\"}\n\n"));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(true))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("data: {\"id\":\"x\"}")) throw new AssertionError("missing upstream chunk");
                    int doneCount = body.split("data: \\[DONE\\]", -1).length - 1;
                    if (doneCount != 1) throw new AssertionError("expected exactly one done marker, got: " + doneCount);
                });

        verify(quotaService, times(1)).recordStreamingUsageOnSuccess(any(), any(), eq(null), any());
        verify(budgetService, times(1)).recordCostOnSuccess(any(), any(), any(), eq(0L), eq(0L), any());
    }

    @Test
    void shouldRecordStreamingUsageWhenUsagePresentInStreamChunk() {
        when(upstreamChatClient.streamWithFallback(any(), any(), any())).thenReturn(Flux.just(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n",
                "data: {\"usage\":{\"total_tokens\":33}}\n\n"
        ));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(true))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("data: [DONE]")) throw new AssertionError("missing done marker");
                });

        verify(quotaService, times(1)).recordStreamingUsageOnSuccess(any(), any(), eq(33L), any());
        verify(budgetService, times(1)).recordCostOnSuccess(any(), any(), any(), eq(0L), eq(0L), any());
    }

    @Test
    void shouldNotRecordStreamingUsageWhenStreamFails() {
        when(upstreamChatClient.streamWithFallback(any(), any(), any())).thenReturn(
                Flux.error(new io.gateway.oss.core.error.GatewayException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout"))
        );

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(true))
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.code").isEqualTo("upstream_timeout");

        verify(quotaService, never()).recordStreamingUsageOnSuccess(any(), any(), any(), any());
        verify(budgetService, never()).recordCostOnSuccess(any(), any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void shouldNotRecordStreamingUsageWhenClientCancelsStream() {
        when(upstreamChatClient.streamWithFallback(any(), any(), any())).thenReturn(
                Flux.just("data: {\"id\":\"x\"}\n\n").concatWith(Flux.never())
        );

        var result = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(true))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        StepVerifier.create(result.getResponseBody())
                .expectNextMatches(body -> body.contains("data: {\"id\":\"x\"}"))
                .thenCancel()
                .verify();

        verify(quotaService, never()).recordStreamingUsageOnSuccess(any(), any(), any(), any());
        verify(budgetService, never()).recordCostOnSuccess(any(), any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void shouldKeepStreamNotSupportedSemanticsAndNotRecordStreamingUsage() {
        when(upstreamChatClient.streamWithFallback(any(), any(), any())).thenReturn(
                Flux.error(new io.gateway.oss.core.error.GatewayException(org.springframework.http.HttpStatus.NOT_IMPLEMENTED, "stream_not_supported", "Streaming is not supported for provider type: anthropic"))
        );

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(true))
                .exchange()
                .expectStatus().isEqualTo(501)
                .expectBody()
                .jsonPath("$.code").isEqualTo("stream_not_supported");

        verify(quotaService, never()).recordStreamingUsageOnSuccess(any(), any(), any(), any());
        verify(budgetService, never()).recordCostOnSuccess(any(), any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void shouldRateLimitSecondRequest() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any())).thenReturn(Mono.just(Map.of("id", "ok")));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("rate_limited");

        verify(upstreamChatClient, times(2)).completeWithFallback(any(), any(), any());
        verify(quotaService, times(2)).recordUsage(any(), any(), any(), any());
        verify(budgetService, times(2)).recordCostOnSuccess(any(), any(), any(), eq(128L), eq(0L), any());
    }

    @Test
    void shouldRejectDailyQuotaAndSkipUpstreamAfterLimitReached() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "quota_1", "usage", Map.of("total_tokens", 1L))));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer daily-quota-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer daily-quota-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("quota_exceeded");

        verify(upstreamChatClient, times(1)).completeWithFallback(any(), any(), any());
        verify(quotaService, times(1)).recordUsage(any(), any(), any(), any());
        verify(budgetService, times(1)).recordCostOnSuccess(any(), any(), any(), eq(1L), eq(0L), any());
    }

    @Test
    void shouldRejectDailyBudgetAndSkipUpstreamAfterLimitReached() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "budget_1", "usage", Map.of("total_tokens", 2L))));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer daily-budget-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer daily-budget-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("budget_exceeded");

        verify(upstreamChatClient, times(1)).completeWithFallback(any(), any(), any());
        verify(quotaService, times(1)).recordUsage(any(), any(), any(), any());
        verify(budgetService, times(1)).recordCostOnSuccess(any(), any(), any(), eq(2L), eq(0L), any());
    }

    @Test
    void shouldRejectMonthlyQuotaAndSkipUpstreamAfterLimitReached() {
        usageStore.addMonthlyUsage("monthly-quota-client-key", 1L, Instant.now());

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer monthly-quota-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("monthly_quota_exceeded");

        verify(upstreamChatClient, never()).completeWithFallback(any(), any(), any());
    }

    @Test
    void shouldRejectMonthlyBudgetAndSkipUpstreamAfterLimitReached() {
        costStore.addMonthlyCost("monthly-budget-client-key", new BigDecimal("0.0002"), Instant.now());

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer monthly-budget-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("monthly_budget_exceeded");

        verify(upstreamChatClient, never()).completeWithFallback(any(), any(), any());
    }

    @Test
    void shouldRejectTpmAndSkipUpstreamAfterReservedUsageReached() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "tpm_1", "usage", Map.of("total_tokens", 100L))));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer tpm-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer tpm-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("tpm_exceeded");

        verify(upstreamChatClient, times(1)).completeWithFallback(any(), any(), any());
    }

    @Test
    void shouldKeepNonStreamingResponseSuccessfulWhenUsageRecordingFails() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.just(Map.of("id", "chatcmpl_1")));
        doThrow(new RuntimeException("usage store down"))
                .when(quotaService).recordUsage(any(), any(), any(), any());

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_1");

        verify(quotaService, times(1)).recordUsage(any(), any(), any(), any());
        verify(budgetService, times(1)).recordCostOnSuccess(any(), any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void shouldNormalizeUpstreamTimeout() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.error(new io.gateway.oss.core.error.GatewayException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout")));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.code").isEqualTo("upstream_timeout")
                .jsonPath("$.message").isEqualTo("Upstream timeout")
                .jsonPath("$.requestId").exists();
    }

    @Test
    void shouldNormalizeUpstreamProviderError() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.error(new io.gateway.oss.core.error.GatewayException(org.springframework.http.HttpStatus.BAD_GATEWAY, "upstream_error", "Upstream provider error")));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.code").isEqualTo("upstream_error")
                .jsonPath("$.requestId").exists();
    }

    @Test
    void shouldRecordMetricsForSuccessfulRequest() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any())).thenReturn(Mono.just(Map.of("id", "chatcmpl_metrics")));

        double countBefore = counterValue("gateway.request.count", "path", "/v1/chat/completions", "method", "POST");
        double outcomeBefore = counterValue("gateway.request.outcome", "path", "/v1/chat/completions", "method", "POST", "outcome", "success", "status", "200");
        long latencyCountBefore = timerCount("gateway.request.latency", "path", "/v1/chat/completions", "method", "POST", "outcome", "success", "status", "200");

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isOk();

        double countAfter = counterValue("gateway.request.count", "path", "/v1/chat/completions", "method", "POST");
        double outcomeAfter = counterValue("gateway.request.outcome", "path", "/v1/chat/completions", "method", "POST", "outcome", "success", "status", "200");
        long latencyCountAfter = timerCount("gateway.request.latency", "path", "/v1/chat/completions", "method", "POST", "outcome", "success", "status", "200");

        if (countAfter - countBefore < 1.0d) throw new AssertionError("request count metric was not incremented");
        if (outcomeAfter - outcomeBefore < 1.0d) throw new AssertionError("success outcome metric was not incremented");
        if (latencyCountAfter - latencyCountBefore < 1L) throw new AssertionError("success latency metric was not recorded");
    }

    @Test
    void shouldRecordMetricsForFailedRequest() {
        double countBefore = counterValue("gateway.request.count", "path", "/v1/chat/completions", "method", "POST");
        double outcomeBefore = counterValue("gateway.request.outcome", "path", "/v1/chat/completions", "method", "POST", "outcome", "failure", "status", "401");
        long latencyCountBefore = timerCount("gateway.request.latency", "path", "/v1/chat/completions", "method", "POST", "outcome", "failure", "status", "401");

        webTestClient.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(false))
                .exchange()
                .expectStatus().isUnauthorized();

        double countAfter = counterValue("gateway.request.count", "path", "/v1/chat/completions", "method", "POST");
        double outcomeAfter = counterValue("gateway.request.outcome", "path", "/v1/chat/completions", "method", "POST", "outcome", "failure", "status", "401");
        long latencyCountAfter = timerCount("gateway.request.latency", "path", "/v1/chat/completions", "method", "POST", "outcome", "failure", "status", "401");

        if (countAfter - countBefore < 1.0d) throw new AssertionError("request count metric was not incremented for failed request");
        if (outcomeAfter - outcomeBefore < 1.0d) throw new AssertionError("failure outcome metric was not incremented");
        if (latencyCountAfter - latencyCountBefore < 1L) throw new AssertionError("failure latency metric was not recorded");
    }

    @Test
    void shouldResolveSceneRouteAndApplyClientDefaults() {
        when(upstreamChatClient.completeWithFallback(any(), any(), any())).thenReturn(Mono.just(Map.of("id", "scene_ok")));

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer scene-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isOk();

        verify(upstreamChatClient).completeWithFallback(
                argThat((ChatCompletionsRequest request) -> request.temperature() != null && request.temperature().equals(0.4d)
                        && request.maxTokens() != null && request.maxTokens().equals(64)),
                argThat((ResolvedRoute route) -> "premium".equals(route.scene())
                        && "openai-primary".equals(route.routeId())
                        && "openai-compatible".equals(route.providerType())
                        && route.fallbackRouteIds().contains("openai-backup")),
                any());
    }

    @Test
    void shouldRejectStreamingForClientWithoutCapability() {
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer no-stream-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(true))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("stream_not_supported");

        verify(upstreamChatClient, never()).streamWithFallback(any(), any(), any());
    }

    @Test
    void shouldCleanupAfterStreamingDisconnect_andAllowSubsequentRequests() {
        // First streaming request that hangs (simulates client disconnect mid-stream)
        when(upstreamChatClient.streamWithFallback(any(), any(), any())).thenReturn(
                Flux.just("data: {\"id\":\"x\"}\n\n").concatWith(Flux.never())
        );

        var result1 = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(true))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);

        // Cancel mid-stream
        StepVerifier.create(result1.getResponseBody())
                .expectNextMatches(body -> body.contains("data: {\"id\":\"x\"}"))
                .thenCancel()
                .verify();

        // Second streaming request should still succeed (concurrency released)
        when(upstreamChatClient.streamWithFallback(any(), any(), any())).thenReturn(
                Flux.just("data: {\"id\":\"y\"}\n\n", "data: [DONE]\n\n")
        );

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validBody(true))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("data: {\"id\":\"y\"}")) throw new AssertionError("missing upstream chunk");
                    if (!body.contains("data: [DONE]")) throw new AssertionError("missing done marker");
                });

        verify(upstreamChatClient, times(2)).streamWithFallback(any(), any(), any());
    }

    @Test
    void shouldRejectClientDefaultMaxTokensWhenItExceedsLimit() {
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer default-over-limit-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("max_tokens_exceeded");

        verify(upstreamChatClient, never()).completeWithFallback(any(), any(), any());
    }

    // ─── circuit breaker admin endpoint assertions (split from gateway-core MockUpstreamIntegrationTest) ───

    @Test
    void shouldShowCircuitBreakerOpenInAdminEndpoints() {
        String adminToken = loginAndGetAdminToken();

        when(upstreamChatClient.completeWithFallback(any(), any(), any()))
                .thenReturn(Mono.error(new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "circuit_breaker_open", "CB open")));

        String failRequestId = "req-cb-admin-" + System.currentTimeMillis();
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .header("X-Request-Id", failRequestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("circuit_breaker_open");

        // Verify request history shows the CB failure
        webTestClient.get().uri("/admin/requests/recent?status=503&limit=10")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requests[0].requestId").isEqualTo(failRequestId)
                .jsonPath("$.requests[0].status").isEqualTo(503)
                .jsonPath("$.requests[0].errorMessage").isEqualTo("circuit_breaker_open");

        // Verify request log detail shows the CB failure
        webTestClient.get().uri("/internal/requests/" + failRequestId)
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.request.requestId").isEqualTo(failRequestId)
                .jsonPath("$.request.status").isEqualTo(503)
                .jsonPath("$.request.errorMessage").isEqualTo("circuit_breaker_open");
    }

    private double counterValue(String name, String... tags) {
        try {
            return meterRegistry.get(name).tags(tags).counter().count();
        } catch (Exception e) {
            System.err.println("[WARN] Meter not found, returning 0: " + name + " tags=" + java.util.Arrays.toString(tags));
            return 0.0d;
        }
    }

    private long timerCount(String name, String... tags) {
        try {
            return meterRegistry.get(name).tags(tags).timer().count();
        } catch (Exception e) {
            System.err.println("[WARN] Meter not found, returning 0: " + name + " tags=" + java.util.Arrays.toString(tags));
            return 0L;
        }
    }

    private Map<String, Object> validBody(boolean stream) {
        return Map.of(
                "model", "gpt-4o-mini",
                "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                "stream", stream,
                "temperature", 0.7,
                "max_tokens", 128
        );
    }

    private String loginAndGetAdminToken() {
        String body = webTestClient.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "admin", "password", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .blockFirst();
        String marker = "\"accessToken\":\"";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
