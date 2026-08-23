package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.gateway.oss.core.routing.RouteLoadBalancer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamChatClientTest {

    private final ProviderKeyResilienceTracker keyResilienceTracker = new ProviderKeyResilienceTracker(resilienceProperties(3), java.time.Clock.systemUTC());
    private final ProviderKeySelector keySelector = new ProviderKeySelector(keyResilienceTracker);

    @Test
    void shouldSelectAdapterByProviderType() {
        FakeAdapter adapter = new FakeAdapter("openai-compatible", Flux.just("chunk"), Mono.just(Map.of("id", "ok")), Mono.just(Map.of("id", "ok")));
        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);

        StepVerifier.create(client.complete(sampleRequest(), sampleRoute("primary", "openai-compatible", List.of())))
                .expectNextMatches(body -> "ok".equals(body.get("id")))
                .verifyComplete();

        if (adapter.completeCalls.get() != 1) {
            throw new AssertionError("expected adapter complete to be called once");
        }
    }

    @Test
    void shouldDispatchToAnthropicAdapterWhenProviderTypeMatches() {
        FakeAdapter openAiAdapter = new FakeAdapter("openai-compatible", Flux.just("chunk"), Mono.just(Map.of("id", "openai")), Mono.just(Map.of("id", "openai")));
        FakeAdapter anthropicAdapter = new FakeAdapter("anthropic", Flux.just("chunk"), Mono.just(Map.of("id", "anthropic")), Mono.just(Map.of("id", "anthropic")));
        UpstreamChatClient client = new UpstreamChatClient(List.of(openAiAdapter, anthropicAdapter), resilienceTracker(3), keySelector, keyResilienceTracker);

        StepVerifier.create(client.complete(sampleRequest(), sampleRoute("primary", "anthropic", List.of())))
                .expectNextMatches(body -> "anthropic".equals(body.get("id")))
                .verifyComplete();

        assertEquals(0, openAiAdapter.completeCalls.get());
        assertEquals(1, anthropicAdapter.completeCalls.get());
    }

    @Test
    void shouldKeepOpenAiDispatchUnchangedWhenAnthropicAdapterAlsoRegistered() {
        FakeAdapter openAiAdapter = new FakeAdapter("openai-compatible", Flux.just("chunk"), Mono.just(Map.of("id", "openai-ok")), Mono.just(Map.of("id", "openai-ok")));
        FakeAdapter anthropicAdapter = new FakeAdapter("anthropic", Flux.just("chunk"), Mono.just(Map.of("id", "anthropic")), Mono.just(Map.of("id", "anthropic")));
        UpstreamChatClient client = new UpstreamChatClient(List.of(openAiAdapter, anthropicAdapter), resilienceTracker(3), keySelector, keyResilienceTracker);

        StepVerifier.create(client.complete(sampleRequest(), sampleRoute("primary", "openai-compatible", List.of())))
                .expectNextMatches(body -> "openai-ok".equals(body.get("id")))
                .verifyComplete();

        assertEquals(1, openAiAdapter.completeCalls.get());
        assertEquals(0, anthropicAdapter.completeCalls.get());
    }

    @Test
    void shouldFallbackThroughAdapterAbstraction() {
        GatewayException primaryFailure = new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout");
        FakeAdapter adapter = new FakeAdapter(
                "openai-compatible",
                Flux.empty(),
                Mono.error(primaryFailure),
                Mono.just(Map.of("id", "fallback-ok"))
        );

        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", List.of("backup"));
        ResolvedRoute backup = sampleRoute("backup", "openai-compatible", List.of());

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, routeId -> backup))
                .expectNextMatches(body -> "fallback-ok".equals(body.get("id")))
                .verifyComplete();

        if (adapter.completeCalls.get() != 2) {
            throw new AssertionError("expected adapter complete to be called for primary and fallback");
        }
    }

    @Test
    void shouldFallbackWhenAnthropicPrimaryFailsAndOpenAiBackupSucceeds() {
        GatewayException primaryFailure = new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout");
        FakeAdapter anthropicAdapter = new FakeAdapter(
                "anthropic",
                Flux.empty(),
                Mono.error(primaryFailure),
                Mono.error(primaryFailure)
        );
        FakeAdapter openAiAdapter = new FakeAdapter(
                "openai-compatible",
                Flux.empty(),
                Mono.just(Map.of("id", "openai-fallback-ok")),
                Mono.just(Map.of("id", "openai-fallback-ok"))
        );

        UpstreamChatClient client = new UpstreamChatClient(List.of(anthropicAdapter, openAiAdapter), resilienceTracker(3), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "anthropic", List.of("backup"));
        ResolvedRoute backup = sampleRoute("backup", "openai-compatible", List.of());

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, routeId -> backup))
                .expectNextMatches(body -> "openai-fallback-ok".equals(body.get("id")))
                .verifyComplete();

        assertEquals(1, anthropicAdapter.completeCalls.get());
        assertEquals(1, openAiAdapter.completeCalls.get());
    }

    @Test
    void shouldReturnConfigErrorForUnsupportedProviderType() {
        FakeAdapter adapter = new FakeAdapter("openai-compatible", Flux.just("chunk"), Mono.just(Map.of("id", "ok")), Mono.just(Map.of("id", "ok")));
        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);

        GatewayException exception = assertThrows(GatewayException.class,
                () -> client.complete(sampleRequest(), sampleRoute("primary", "unsupported-provider", List.of())));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        assertEquals("config_error", exception.getCode());
    }

    @Test
    void shouldRespectRetryBudgetEvenWhenMoreFallbacksExist() {
        GatewayException primaryFailure = new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout");
        GatewayException backupFailure = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Upstream provider error");
        FakeAdapter adapter = new FakeAdapter(
                "openai-compatible",
                Flux.empty(),
                Mono.error(primaryFailure),
                Mono.error(backupFailure)
        );

        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(2), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 2, List.of("backup-a", "backup-b"));
        AtomicInteger resolveCalls = new AtomicInteger();

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, routeId -> {
                    resolveCalls.incrementAndGet();
                    return sampleRoute(routeId, "openai-compatible", 2, List.of());
                }))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getCode().equals("upstream_error"))
                .verify();

        assertEquals(2, adapter.completeCalls.get());
        assertEquals(1, resolveCalls.get());
        assertSame(backupFailure, adapter.lastError.get());
    }

    @Test
    void shouldSkipUnhealthyFallbackCandidates() {
        GatewayException primaryFailure = new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout");
        FakeAdapter adapter = new FakeAdapter(
                "openai-compatible",
                Flux.empty(),
                Mono.error(primaryFailure),
                Mono.just(Map.of("id", "healthy-fallback"))
        );

        GatewayProperties properties = resilienceProperties(2);
        RouteResilienceTracker tracker = new RouteResilienceTracker(properties, java.time.Clock.systemUTC());
        tracker.recordRetryableFailure(sampleRoute("backup-a", "openai-compatible", 2, List.of()));
        tracker.recordRetryableFailure(sampleRoute("backup-a", "openai-compatible", 2, List.of()));

        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), tracker, keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 3, List.of("backup-a", "backup-b"));
        AtomicBoolean resolvedUnhealthy = new AtomicBoolean(false);

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, routeId -> {
                    if ("backup-a".equals(routeId)) {
                        resolvedUnhealthy.set(true);
                    }
                    return sampleRoute(routeId, "openai-compatible", 3, List.of());
                }))
                .expectNextMatches(body -> "healthy-fallback".equals(body.get("id")))
                .verifyComplete();

        assertFalse(resolvedUnhealthy.get());
        assertEquals(2, adapter.completeCalls.get());
    }

    @Test
    void shouldNotMarkRouteUnhealthyForNonRetryableFailures() {
        GatewayException badRequestFailure = new GatewayException(HttpStatus.BAD_REQUEST, "bad_request", "invalid upstream request");
        FakeAdapter adapter = new FakeAdapter(
                "openai-compatible",
                Flux.empty(),
                Mono.error(badRequestFailure),
                Mono.just(Map.of())
        );

        GatewayProperties properties = resilienceProperties(2);
        RouteResilienceTracker tracker = new RouteResilienceTracker(properties, java.time.Clock.systemUTC());
        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), tracker, keySelector, keyResilienceTracker);
        ResolvedRoute route = sampleRoute("primary", "openai-compatible", 3, List.of("backup"));

        StepVerifier.create(client.complete(sampleRequest(), route))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getCode().equals("bad_request"))
                .verify();

        assertTrue(tracker.isAvailable(route));
        assertSame(badRequestFailure, adapter.lastError.get());
    }

    @Test
    void shouldReturnNormalizedNotImplementedWhenStreamingUnsupportedByAdapter() {
        NonStreamingAdapter adapter = new NonStreamingAdapter("anthropic");
        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(2), keySelector, keyResilienceTracker);

        StepVerifier.create(client.stream(sampleRequest(), sampleRoute("primary", "anthropic", List.of())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.NOT_IMPLEMENTED
                        && gatewayException.getCode().equals("stream_not_supported"))
                .verify();

        assertEquals(0, adapter.streamCalls.get());
    }

    @Test
    void shouldKeepStreamNotSupportedDecisionOnPrimaryBeforeLoadBalancerSelection() {
        NonStreamingAdapter nonStreaming = new NonStreamingAdapter("anthropic");
        RecordingAdapter openai = new RecordingAdapter(
                "openai-compatible",
                Mono.just(Map.of("id", "ok")),
                Mono.just(Map.of("id", "ok"))
        );

        GatewayProperties properties = resilienceProperties(3);
        properties.getLoadBalancer().setEnabled(true);
        RouteLoadBalancer loadBalancer = new RouteLoadBalancer(properties, resilienceTracker(3));
        UpstreamChatClient client = new UpstreamChatClient(List.of(nonStreaming, openai), resilienceTracker(3), keySelector, keyResilienceTracker, loadBalancer);

        ResolvedRoute primary = sampleRoute("primary", "anthropic", 3, List.of("backup"), 1);
        ResolvedRoute backup = sampleRoute("backup", "openai-compatible", 3, List.of(), 5);

        StepVerifier.create(client.streamWithSelection(sampleRequest(), primary, routeId -> backup))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.NOT_IMPLEMENTED
                        && "stream_not_supported".equals(gatewayException.getCode()))
                .verify();

        assertEquals(0, nonStreaming.streamCalls.get());
        assertEquals(0, openai.seenRouteIds.size());
    }

    @Test
    void shouldKeepLegacyPrimaryThenFallbackOrderWhenLoadBalancerDisabled() {
        GatewayException primaryFailure = new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout");
        RecordingAdapter adapter = new RecordingAdapter(
                "openai-compatible",
                Mono.error(primaryFailure),
                Mono.just(Map.of("id", "fallback-ok"))
        );
        GatewayProperties properties = resilienceProperties(3);
        properties.getLoadBalancer().setEnabled(false);
        RouteLoadBalancer loadBalancer = new RouteLoadBalancer(properties, resilienceTracker(3));
        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker, loadBalancer);

        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 3, List.of("backup-a", "backup-b"), 1);
        ResolvedRoute backupA = sampleRoute("backup-a", "openai-compatible", 3, List.of(), 10);

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, routeId -> backupA))
                .expectNextMatches(body -> "fallback-ok".equals(body.get("id")))
                .verifyComplete();

        assertEquals(List.of("primary", "backup-a"), adapter.seenRouteIds);
    }

    @Test
    void shouldUseLoadBalancedRouteSelectionForStreamingWhenEnabled() {
        RecordingAdapter adapter = new RecordingAdapter(
                "openai-compatible",
                Mono.just(Map.of("id", "ok")),
                Mono.just(Map.of("id", "ok"))
        );
        GatewayProperties properties = resilienceProperties(3);
        properties.getLoadBalancer().setEnabled(true);
        RouteResilienceTracker tracker = resilienceTracker(3);
        RouteLoadBalancer loadBalancer = new RouteLoadBalancer(properties, tracker);
        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), tracker, keySelector, keyResilienceTracker, loadBalancer);

        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 3, List.of("backup"), 1);
        ResolvedRoute backup = sampleRoute("backup", "openai-compatible", 3, List.of(), 3);

        for (int i = 0; i < 4; i++) {
            StepVerifier.create(client.streamWithSelection(sampleRequest(), primary, routeId -> backup))
                    .expectNext("data: {\"id\":\"ok\"}\n\n")
                    .verifyComplete();
        }

        long backupCount = adapter.seenRouteIds.stream().filter("backup"::equals).count();
        long primaryCount = adapter.seenRouteIds.stream().filter("primary"::equals).count();
        assertEquals(3L, backupCount);
        assertEquals(1L, primaryCount);
    }

    @Test
    void shouldRetryAnotherCandidateWhenSelectedRouteFailsUnderLoadBalancing() {
        GatewayException retryable = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Upstream provider error");
        RecordingAdapter adapter = new RecordingAdapter(
                "openai-compatible",
                Mono.error(retryable),
                Mono.just(Map.of("id", "recovered"))
        );

        GatewayProperties properties = resilienceProperties(3);
        properties.getLoadBalancer().setEnabled(true);
        RouteResilienceTracker tracker = resilienceTracker(3);
        RouteLoadBalancer loadBalancer = new RouteLoadBalancer(properties, tracker);
        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), tracker, keySelector, keyResilienceTracker, loadBalancer);

        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 3, List.of("backup"), 1);
        ResolvedRoute backup = sampleRoute("backup", "openai-compatible", 3, List.of(), 3);

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, routeId -> backup))
                .expectNextMatches(body -> "recovered".equals(body.get("id")))
                .verifyComplete();

        assertEquals(List.of("backup", "primary"), adapter.seenRouteIds);
    }

    @Test
    void shouldFallbackThroughDeepChainWhenMultipleIntermediatesFail() {
        GatewayException timeoutFailure = new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout");
        GatewayException badGatewayFailure = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Upstream provider error");

        FakeAdapter anthropicAdapter = new FakeAdapter(
                "anthropic",
                Flux.empty(),
                Mono.error(timeoutFailure),        // primary call: timeout
                Mono.error(badGatewayFailure)      // backup-a call: 502
        );
        FakeAdapter openaiAdapter = new FakeAdapter(
                "openai-compatible",
                Flux.empty(),
                Mono.just(Map.of("id", "deep-fallback-ok")),  // backup-b call: success
                Mono.just(Map.of("id", "deep-fallback-ok"))
        );

        UpstreamChatClient client = new UpstreamChatClient(List.of(anthropicAdapter, openaiAdapter), resilienceTracker(4), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "anthropic", 4, List.of("backup-a", "backup-b"));
        ResolvedRoute backupA = sampleRoute("backup-a", "anthropic", 4, List.of());
        ResolvedRoute backupB = sampleRoute("backup-b", "openai-compatible", 4, List.of());

        java.util.function.Function<String, ResolvedRoute> resolver = routeId -> {
            return switch (routeId) {
                case "backup-a" -> backupA;
                case "backup-b" -> backupB;
                default -> sampleRoute(routeId, "openai-compatible", 4, List.of());
            };
        };

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, resolver))
                .expectNextMatches(body -> "deep-fallback-ok".equals(body.get("id")))
                .verifyComplete();

        assertEquals(2, anthropicAdapter.completeCalls.get());
        assertEquals(1, openaiAdapter.completeCalls.get());
    }

    @Test
    void shouldRecordRetryableFailureOnTrackerWhenStreamFails() {
        GatewayException streamError = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Upstream provider error");

        FakeAdapter adapter = new FakeAdapter(
                "openai-compatible",
                Flux.concat(Flux.just("data: {\"id\":\"partial\"}\n\n"), Flux.error(streamError)),
                Mono.just(Map.of()),
                Mono.just(Map.of())
        );

        // threshold=1 so a single recorded failure trips the circuit immediately
        GatewayProperties properties = resilienceProperties(1);
        properties.getResilience().setRetryableFailureThreshold(1);
        RouteResilienceTracker tracker = new RouteResilienceTracker(properties, java.time.Clock.systemUTC());
        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), tracker, keySelector, keyResilienceTracker);
        ResolvedRoute route = sampleRoute("stream-fail", "openai-compatible", 3, List.of());

        assertTrue(tracker.isAvailable(route));

        StepVerifier.create(client.stream(sampleRequest(), route))
                .expectNext("data: {\"id\":\"partial\"}\n\n")
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();

        // Stream failure should have been recorded as a retryable failure by doOnError
        assertFalse(tracker.isAvailable(route));
    }

    @Test
    void shouldNotAttemptFallbackWhenMaxAttemptsIsOne() {
        GatewayException primaryFailure = new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Upstream timeout");
        FakeAdapter adapter = new FakeAdapter(
                "openai-compatible",
                Flux.empty(),
                Mono.error(primaryFailure),
                Mono.just(Map.of("id", "should-not-reach"))
        );

        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 1, List.of("backup"));
        AtomicBoolean fallbackResolved = new AtomicBoolean(false);

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, routeId -> {
                    fallbackResolved.set(true);
                    return sampleRoute(routeId, "openai-compatible", 1, List.of());
                }))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && "upstream_timeout".equals(gatewayException.getCode()))
                .verify();

        assertFalse(fallbackResolved.get(), "Fallback resolver should not be called when maxAttempts=1");
        assertEquals(1, adapter.completeCalls.get());
    }

    @Test
    void shouldFallbackWhenRetryOperatorWrapsRetryableGatewayException() {
        GatewayException primaryFailure = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Upstream provider error");
        AtomicInteger primarySubscriptions = new AtomicInteger();
        RouteAwareAdapter adapter = new RouteAwareAdapter(
                "openai-compatible",
                route -> {
                    if ("primary".equals(route.routeId())) {
                        return Mono.defer(() -> {
                            primarySubscriptions.incrementAndGet();
                            return Mono.error(primaryFailure);
                        });
                    }
                    return Mono.just(Map.of("id", "wrapped-fallback-ok"));
                }
        );

        GatewayProperties properties = resilienceProperties(3);
        properties.getResilience().setRetryMaxAttempts(2);
        Resilience4jCircuitBreakerService resilienceService = new Resilience4jCircuitBreakerService(
                properties,
                new GatewayMetricsRecorder(new SimpleMeterRegistry())
        );

        UpstreamChatClient client = new UpstreamChatClient(
                List.of(adapter),
                resilienceTracker(3),
                keySelector,
                keyResilienceTracker,
                new RouteLoadBalancer(properties, resilienceTracker(3)),
                resilienceService
        );
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 3, List.of("backup"));
        ResolvedRoute backup = sampleRoute("backup", "openai-compatible", 3, List.of());

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, routeId -> backup))
                .expectNextMatches(body -> "wrapped-fallback-ok".equals(body.get("id")))
                .verifyComplete();

        assertEquals(2, adapter.completeCalls.get());
        assertEquals(2, primarySubscriptions.get());
        assertEquals(List.of("primary", "backup"), adapter.seenRouteIds);
    }

    @Test
    void shouldKeepMockScenarioWhenCallingFallbackRoute() {
        GatewayException primaryFailure = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Upstream provider error");
        AtomicReference<ChatCompletionsRequest> primarySeenRequest = new AtomicReference<>();
        AtomicReference<ChatCompletionsRequest> fallbackSeenRequest = new AtomicReference<>();

        RouteAwareAdapter adapter = new RouteAwareAdapter(
                "openai-compatible",
                route -> Mono.defer(() -> {
                    if ("primary".equals(route.routeId())) {
                        return Mono.error(primaryFailure);
                    }
                    return Mono.just(Map.of("id", "fallback-ok"));
                }),
                (request, route) -> {
                    if ("primary".equals(route.routeId())) {
                        primarySeenRequest.set(request);
                    } else if ("backup".equals(route.routeId())) {
                        fallbackSeenRequest.set(request);
                    }
                }
        );

        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 3, List.of("backup"));
        ResolvedRoute backup = sampleRoute("backup", "openai-compatible", 3, List.of());
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                false,
                0.7d,
                128,
                null,
                null,
                null,
                Map.of("mock_scenario", "provider-error", "custom_flag", true)
        );

        StepVerifier.create(client.completeWithFallback(request, primary, routeId -> backup))
                .expectNextMatches(body -> "fallback-ok".equals(body.get("id")))
                .verifyComplete();

        assertEquals("provider-error", primarySeenRequest.get().extras().get("mock_scenario"));
        assertEquals("provider-error", fallbackSeenRequest.get().extras().get("mock_scenario"));
        assertEquals(Boolean.TRUE, fallbackSeenRequest.get().extras().get("custom_flag"));
    }

    @Test
    void shouldFallbackWhenCircuitBreakerIsOpen() {
        GatewayException primaryFailure = new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "circuit_breaker_open", "Circuit breaker open");
        RouteAwareAdapter adapter = new RouteAwareAdapter(
                "openai-compatible",
                route -> Mono.defer(() -> {
                    if ("primary".equals(route.routeId())) {
                        return Mono.error(primaryFailure);
                    }
                    return Mono.just(Map.of("id", "fallback-after-open-circuit"));
                })
        );

        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 3, List.of("backup"));
        ResolvedRoute backup = sampleRoute("backup", "openai-compatible", 3, List.of());

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, routeId -> backup))
                .expectNextMatches(body -> "fallback-after-open-circuit".equals(body.get("id")))
                .verifyComplete();

        assertEquals(List.of("primary", "backup"), adapter.seenRouteIds);
    }

    @Test
    void shouldNotFallbackAfterFirstBusinessStreamChunk() {
        GatewayException primaryMidStreamFailure = new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "mid-stream failure");
        AtomicInteger backupStreamCalls = new AtomicInteger();

        ChatProviderAdapter adapter = new ChatProviderAdapter() {
            @Override
            public String providerType() {
                return "openai-compatible";
            }

            @Override
            public Mono<Map<String, Object>> complete(ChatCompletionsRequest request, ResolvedRoute route) {
                return Mono.just(Map.of("id", "unused"));
            }

            @Override
            public Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route) {
                if ("primary".equals(route.routeId())) {
                    return Flux.concat(
                            Flux.just("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"),
                            Flux.error(primaryMidStreamFailure)
                    );
                }
                backupStreamCalls.incrementAndGet();
                return Flux.just("data: {\"choices\":[{\"delta\":{\"content\":\"from-backup\"}}]}\n\n");
            }
        };

        UpstreamChatClient client = new UpstreamChatClient(List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", 3, List.of("backup"));
        ResolvedRoute backup = sampleRoute("backup", "openai-compatible", 3, List.of());

        StepVerifier.create(client.streamWithFallback(sampleRequest(), primary, routeId -> backup))
                .expectNext("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n")
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();

        assertEquals(0, backupStreamCalls.get(), "fallback stream must not run after first business chunk");
    }

    // ===== Fallback ordering =====

    @Test
    void shouldRespectFallbackOrderWhenFirstFallbackSucceeds() {
        RouteAwareAdapter adapter = new RouteAwareAdapter(
                "openai-compatible",
                route -> "primary".equals(route.routeId())
                        ? Mono.error(new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Primary timeout"))
                        : Mono.just(Map.of("id", route.routeId() + "-ok"))
        );

        UpstreamChatClient client = new UpstreamChatClient(
                List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", List.of("fallback1", "fallback2"));
        ResolvedRoute fallback1 = sampleRoute("fallback1", "openai-compatible", List.of());
        ResolvedRoute fallback2 = sampleRoute("fallback2", "openai-compatible", List.of());

        java.util.function.Function<String, ResolvedRoute> resolver = routeId -> {
            if ("fallback1".equals(routeId)) return fallback1;
            if ("fallback2".equals(routeId)) return fallback2;
            return sampleRoute(routeId, "openai-compatible", List.of());
        };

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, resolver))
                .expectNextMatches(body -> "fallback1-ok".equals(body.get("id")))
                .verifyComplete();

        assertEquals(List.of("primary", "fallback1"), adapter.seenRouteIds);
    }

    @Test
    void shouldSkipFailingFallbackAndTryNext() {
        RouteAwareAdapter adapter = new RouteAwareAdapter(
                "openai-compatible",
                route -> {
                    if ("primary".equals(route.routeId()) || "fallback1".equals(route.routeId())) {
                        return Mono.error(new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Timeout"));
                    }
                    return Mono.just(Map.of("id", "fallback2-ok"));
                }
        );

        UpstreamChatClient client = new UpstreamChatClient(
                List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", List.of("fallback1", "fallback2"));
        ResolvedRoute fallback1 = sampleRoute("fallback1", "openai-compatible", List.of());
        ResolvedRoute fallback2 = sampleRoute("fallback2", "openai-compatible", List.of());

        java.util.function.Function<String, ResolvedRoute> resolver = routeId -> {
            if ("fallback1".equals(routeId)) return fallback1;
            if ("fallback2".equals(routeId)) return fallback2;
            return sampleRoute(routeId, "openai-compatible", List.of());
        };

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, resolver))
                .expectNextMatches(body -> "fallback2-ok".equals(body.get("id")))
                .verifyComplete();

        assertEquals(List.of("primary", "fallback1", "fallback2"), adapter.seenRouteIds);
    }

    @Test
    void shouldPropagateErrorWhenAllFallbacksFail() {
        RouteAwareAdapter adapter = new RouteAwareAdapter(
                "openai-compatible",
                route -> Mono.error(new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "All upstreams timeout"))
        );

        UpstreamChatClient client = new UpstreamChatClient(
                List.of(adapter), resilienceTracker(3), keySelector, keyResilienceTracker);
        ResolvedRoute primary = sampleRoute("primary", "openai-compatible", List.of("fallback1", "fallback2"));
        ResolvedRoute fallback1 = sampleRoute("fallback1", "openai-compatible", List.of());
        ResolvedRoute fallback2 = sampleRoute("fallback2", "openai-compatible", List.of());

        java.util.function.Function<String, ResolvedRoute> resolver = routeId -> {
            if ("fallback1".equals(routeId)) return fallback1;
            if ("fallback2".equals(routeId)) return fallback2;
            return sampleRoute(routeId, "openai-compatible", List.of());
        };

        StepVerifier.create(client.completeWithFallback(sampleRequest(), primary, resolver))
                .expectErrorMatches(error -> error instanceof GatewayException ge
                        && "upstream_timeout".equals(ge.getCode()))
                .verify();

        assertEquals(List.of("primary", "fallback1", "fallback2"), adapter.seenRouteIds);
    }

    private ChatCompletionsRequest sampleRequest() {
        return new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                false,
                0.7d,
                128
        );
    }

    private ResolvedRoute sampleRoute(String routeId, String providerType, List<String> fallbackRouteIds) {
        return sampleRoute(routeId, providerType, 3, fallbackRouteIds);
    }

    private ResolvedRoute sampleRoute(String routeId, String providerType, int maxAttempts, List<String> fallbackRouteIds) {
        return new ResolvedRoute(
                "gpt-4o-mini",
                routeId,
                "default-chat",
                "openai",
                providerType,
                "gpt-4o-mini",
                "http://localhost:18080",
                "test-key",
                Duration.ofSeconds(1),
                maxAttempts,
                fallbackRouteIds
        );
    }

    private ResolvedRoute sampleRoute(String routeId,
                                      String providerType,
                                      int maxAttempts,
                                      List<String> fallbackRouteIds,
                                      int weight) {
        return new ResolvedRoute(
                "gpt-4o-mini",
                routeId,
                "default-chat",
                "openai",
                providerType,
                "gpt-4o-mini",
                "http://localhost:18080",
                List.of("test-key"),
                "test-key",
                Duration.ofSeconds(1),
                maxAttempts,
                fallbackRouteIds,
                weight
        );
    }

    private RouteResilienceTracker resilienceTracker(int maxAttempts) {
        return new RouteResilienceTracker(resilienceProperties(maxAttempts), java.time.Clock.systemUTC());
    }

    private GatewayProperties resilienceProperties(int maxAttempts) {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().setMaxAttempts(maxAttempts);
        properties.getResilience().setRetryableFailureThreshold(2);
        properties.getResilience().setFailureWindow(Duration.ofSeconds(30));
        properties.getResilience().setOpenDuration(Duration.ofSeconds(30));
        return properties;
    }

    private static final class FakeAdapter implements ChatProviderAdapter {
        private final String providerType;
        private final AtomicInteger completeCalls = new AtomicInteger();
        private final Flux<String> streamResponse;
        private final Mono<Map<String, Object>> firstCompletion;
        private final Mono<Map<String, Object>> nextCompletion;
        private final java.util.concurrent.atomic.AtomicReference<Throwable> lastError = new java.util.concurrent.atomic.AtomicReference<>();

        private FakeAdapter(String providerType,
                            Flux<String> streamResponse,
                            Mono<Map<String, Object>> firstCompletion,
                            Mono<Map<String, Object>> nextCompletion) {
            this.providerType = providerType;
            this.streamResponse = streamResponse;
            this.firstCompletion = firstCompletion;
            this.nextCompletion = nextCompletion;
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public Mono<Map<String, Object>> complete(ChatCompletionsRequest request, ResolvedRoute route) {
            int call = completeCalls.incrementAndGet();
            return (call == 1 ? firstCompletion : nextCompletion)
                    .doOnError(lastError::set);
        }

        @Override
        public Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route) {
            return streamResponse;
        }
    }

    private static final class NonStreamingAdapter implements ChatProviderAdapter {
        private final String providerType;
        private final AtomicInteger streamCalls = new AtomicInteger();

        private NonStreamingAdapter(String providerType) {
            this.providerType = providerType;
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public Mono<Map<String, Object>> complete(ChatCompletionsRequest request, ResolvedRoute route) {
            return Mono.just(Map.of("id", "ok"));
        }

        @Override
        public Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route) {
            streamCalls.incrementAndGet();
            return Flux.just("chunk");
        }
    }

    private static final class RecordingAdapter implements ChatProviderAdapter {
        private final String providerType;
        private final AtomicInteger completeCalls = new AtomicInteger();
        private final Mono<Map<String, Object>> firstCompletion;
        private final Mono<Map<String, Object>> nextCompletion;
        private final List<String> seenRouteIds = new java.util.concurrent.CopyOnWriteArrayList<>();

        private RecordingAdapter(String providerType,
                                 Mono<Map<String, Object>> firstCompletion,
                                 Mono<Map<String, Object>> nextCompletion) {
            this.providerType = providerType;
            this.firstCompletion = firstCompletion;
            this.nextCompletion = nextCompletion;
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public Mono<Map<String, Object>> complete(ChatCompletionsRequest request, ResolvedRoute route) {
            seenRouteIds.add(route.routeId());
            int call = completeCalls.incrementAndGet();
            return call == 1 ? firstCompletion : nextCompletion;
        }

        @Override
        public Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route) {
            seenRouteIds.add(route.routeId());
            return Flux.just("data: {\"id\":\"ok\"}\n\n");
        }
    }

    private static final class RouteAwareAdapter implements ChatProviderAdapter {
        private final String providerType;
        private final AtomicInteger completeCalls = new AtomicInteger();
        private final java.util.function.Function<ResolvedRoute, Mono<Map<String, Object>>> completionFactory;
        private final java.util.function.BiConsumer<ChatCompletionsRequest, ResolvedRoute> requestObserver;
        private final List<String> seenRouteIds = new java.util.concurrent.CopyOnWriteArrayList<>();

        private RouteAwareAdapter(String providerType,
                                  java.util.function.Function<ResolvedRoute, Mono<Map<String, Object>>> completionFactory) {
            this(providerType, completionFactory, (request, route) -> {
            });
        }

        private RouteAwareAdapter(String providerType,
                                  java.util.function.Function<ResolvedRoute, Mono<Map<String, Object>>> completionFactory,
                                  java.util.function.BiConsumer<ChatCompletionsRequest, ResolvedRoute> requestObserver) {
            this.providerType = providerType;
            this.completionFactory = completionFactory;
            this.requestObserver = requestObserver;
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public Mono<Map<String, Object>> complete(ChatCompletionsRequest request, ResolvedRoute route) {
            seenRouteIds.add(route.routeId());
            completeCalls.incrementAndGet();
            requestObserver.accept(request, route);
            return completionFactory.apply(route);
        }

        @Override
        public Flux<String> stream(ChatCompletionsRequest request, ResolvedRoute route) {
            seenRouteIds.add(route.routeId());
            return Flux.empty();
        }
    }
}
