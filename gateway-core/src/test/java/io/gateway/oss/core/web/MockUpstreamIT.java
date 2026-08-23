package io.gateway.oss.core.web;

import io.gateway.oss.core.util.BatchFlusher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class MockUpstreamIT {

    private static DisposableServer primaryServer;
    private static DisposableServer fallbackServer;
    private static final AtomicInteger fallbackServerHits = new AtomicInteger();
    private static final AtomicInteger primaryTimeoutRouteHits = new AtomicInteger();
    private static final AtomicInteger primaryProviderErrorRouteHits = new AtomicInteger();
    private static final AtomicInteger primaryStreamTimeoutRouteHits = new AtomicInteger();
    private static final AtomicInteger primaryScenarioProviderErrorHits = new AtomicInteger();
    private static final AtomicReference<String> lastSeenUpstreamRequestId = new AtomicReference<>();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private BatchFlusher batchFlusher;

    @BeforeEach
    void setUp() throws InterruptedException {
        batchFlusher.setSynchronous(true);
        // Allow any open circuit breaker to transition to HALF_OPEN
        // (wait-duration-in-open-state is 200ms, so 300ms is sufficient)
        Thread.sleep(300);
    }

    @BeforeAll
    static void startMockServer() {
        primaryServer = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .post("/v1/chat/completions", (request, response) -> request.receive().aggregate().asString().flatMap(body -> {
                            if (body.contains("stream-timeout-model") && body.contains("\"stream\":true")) {
                                primaryStreamTimeoutRouteHits.incrementAndGet();
                                return Mono.delay(Duration.ofMillis(1500))
                                        .then(response.status(200)
                                                .header("Content-Type", "text/event-stream")
                                                .sendString(Mono.just("data: {\"id\":\"late-stream\"}\n\n"))
                                                .then());
                            }
                            if (body.contains("\"mock_scenario\":\"provider-error\"")) {
                                primaryScenarioProviderErrorHits.incrementAndGet();
                                return response.status(502)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"error\":{\"message\":\"scenario provider error\"}}"))
                                        .then();
                            }
                            if (body.contains("\"model\":\"timeout-model\"")) {
                                primaryTimeoutRouteHits.incrementAndGet();
                                return Mono.delay(Duration.ofMillis(1500))
                                        .then(response.status(200)
                                                .header("Content-Type", "application/json")
                                                .sendString(Mono.just("{\"id\":\"late\"}"))
                                                .then());
                            }
                            if (body.contains("provider-error-model")) {
                                primaryProviderErrorRouteHits.incrementAndGet();
                                return response.status(502)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"error\":\"bad gateway\"}"))
                                        .then();
                            }
                            if (body.contains("stream-fail-model")) {
                                primaryProviderErrorRouteHits.incrementAndGet();
                                return response.status(502)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"error\":\"stream failed\"}"))
                                        .then();
                            }
                            if (body.contains("stream-usage-model") && body.contains("\"stream\":true")) {
                                return response.status(200)
                                        .header("Content-Type", "text/event-stream")
                                        .sendString(Mono.just(
                                            "data: {\"id\":\"chunk-1\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                                            "data: {\"id\":\"chunk-2\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                                        ))
                                        .then();
                            }
                            if (body.contains("stream-done-then-extra-model") && body.contains("\"stream\":true")) {
                                return response.status(200)
                                        .header("Content-Type", "text/event-stream")
                                        .sendString(Mono.just(
                                                "data: {\"id\":\"chunk-before-done\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                                                "data: [DONE]\n\n" +
                                                "data: {\"id\":\"chunk-after-done\",\"choices\":[{\"delta\":{\"content\":\"SHOULD_NOT_APPEAR\"}}]}\n\n"
                                        ))
                                        .then();
                            }
                            if (body.contains("stream-missing-done-model") && body.contains("\"stream\":true")) {
                                return response.status(200)
                                        .header("Content-Type", "text/event-stream")
                                        .sendString(Mono.just(
                                                "data: {\"id\":\"chunk-before-terminal\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
                                        ))
                                        .then();
                            }
                            if (body.contains("stream-partial-then-error-model") && body.contains("\"stream\":true")) {
                                return response.status(200)
                                        .header("Content-Type", "text/event-stream")
                                        .sendString(Flux.just(
                                                "data: {\"id\":\"chunk-before-error\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
                                        ).concatWith(Mono.error(new RuntimeException("upstream stream interrupted"))))
                                        .then();
                            }
                            if (body.contains("x-request-id-model")) {
                                lastSeenUpstreamRequestId.set(request.requestHeaders().get("X-Request-Id"));
                                return response.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"id\":\"chatcmpl_reqid\",\"object\":\"chat.completion\"}"))
                                        .then();
                            }
                            if (body.contains("cb-fail-model") && !body.contains("cb_recover")) {
                                primaryProviderErrorRouteHits.incrementAndGet();
                                return response.status(502)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"error\":\"cb fail\"}"))
                                        .then();
                            }
                            if (body.contains("cb-recover-model") || body.contains("cb_recover")) {
                                return response.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"id\":\"chatcmpl_cb_recovered\",\"object\":\"chat.completion\"}"))
                                        .then();
                            }
                            if (body.contains("\"stream\":true")) {
                                return response.status(200)
                                        .header("Content-Type", "text/event-stream")
                                        .sendString(Mono.just("data: {\"id\":\"chunk-1\"}\n\n"))
                                        .then();
                            }
                            return response.status(200)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just("{\"id\":\"chatcmpl_mock\",\"object\":\"chat.completion\"}"))
                                    .then();
                        })))
                .bindNow();

        fallbackServer = HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .post("/v1/chat/completions", (request, response) -> request.receive().aggregate().asString().flatMap(body -> {
                            fallbackServerHits.incrementAndGet();
                            if (body.contains("\"stream\":true")) {
                                return response.status(200)
                                        .header("Content-Type", "text/event-stream")
                                        .sendString(Mono.just("data: {\"id\":\"fallback-chunk-1\",\"choices\":[{\"delta\":{\"content\":\"Fallback\"}}]}\n\n"))
                                        .then();
                            }
                            return response.status(200)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just("{\"id\":\"chatcmpl_fallback\",\"object\":\"chat.completion\"}"))
                                    .then();
                        })))
                .bindNow();
    }

    @AfterAll
    static void stopMockServer() {
        if (primaryServer != null) {
            primaryServer.disposeNow();
        }
        if (fallbackServer != null) {
            fallbackServer.disposeNow();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.shared-state.backend", () -> "in_memory");
        registry.add("gateway.providers.openai.base-url", () -> "http://localhost:" + primaryServer.port());
        registry.add("gateway.providers.openai.timeout", () -> "1s");
        registry.add("gateway.providers.openai.api-key", () -> "primary-key");
        registry.add("gateway.providers.openai-fallback.base-url", () -> "http://localhost:" + fallbackServer.port());
        registry.add("gateway.providers.openai-fallback.timeout", () -> "1s");
        registry.add("gateway.providers.openai-fallback.api-key", () -> "fallback-key");
        registry.add("gateway.routes.gpt-4o-mini.provider", () -> "openai");
        registry.add("gateway.routes.gpt-4o-mini.upstream-model", () -> "gpt-4o-mini");
        registry.add("gateway.routes.gpt-4o-mini-scenario.provider", () -> "openai");
        registry.add("gateway.routes.gpt-4o-mini-scenario.upstream-model", () -> "gpt-4o-mini");
        registry.add("gateway.routes.gpt-4o-mini-scenario.fallback-routes[0]", () -> "fallback-ok");
        registry.add("gateway.routes.fallback-ok.provider", () -> "openai-fallback");
        registry.add("gateway.routes.fallback-ok.upstream-model", () -> "fallback-model");
        registry.add("gateway.routes.gpt-4o-mini-timeout.provider", () -> "openai");
        registry.add("gateway.routes.gpt-4o-mini-timeout.upstream-model", () -> "timeout-model");
        registry.add("gateway.routes.gpt-4o-mini-timeout.fallback-routes[0]", () -> "fallback-ok");
        registry.add("gateway.routes.gpt-4o-mini-provider-error.provider", () -> "openai");
        registry.add("gateway.routes.gpt-4o-mini-provider-error.upstream-model", () -> "provider-error-model");
        registry.add("gateway.routes.gpt-4o-mini-provider-error.fallback-routes[0]", () -> "fallback-ok");
        registry.add("gateway.routes.stream-timeout-model.provider", () -> "openai");
        registry.add("gateway.routes.stream-timeout-model.upstream-model", () -> "stream-timeout-model");
        registry.add("gateway.routes.stream-timeout-model.fallback-routes[0]", () -> "fallback-ok");
        registry.add("gateway.clients.demo-client-key.enabled", () -> true);
        registry.add("gateway.clients.demo-client-key.allowed-models", () -> "gpt-4o-mini,gpt-4o-mini-scenario,gpt-4o-mini-timeout,gpt-4o-mini-provider-error,stream-fail-model,stream-usage-model,stream-timeout-model,stream-done-then-extra-model,stream-missing-done-model,stream-partial-then-error-model,x-request-id-model,cb-fail-model");
        registry.add("gateway.clients.demo-client-key.defaults.scene", () -> "");
        registry.add("gateway.clients.unknown-model-client-key.enabled", () -> true);
        registry.add("gateway.clients.unknown-model-client-key.allowed-models", () -> "gpt-4o-missing");
        registry.add("gateway.limit.requests-per-window", () -> 100);

        // New routes for new models
        registry.add("gateway.routes.stream-fail-model.provider", () -> "openai");
        registry.add("gateway.routes.stream-fail-model.upstream-model", () -> "stream-fail-model");
        registry.add("gateway.routes.stream-fail-model.fallback-routes[0]", () -> "fallback-ok");
        registry.add("gateway.routes.stream-usage-model.provider", () -> "openai");
        registry.add("gateway.routes.stream-usage-model.upstream-model", () -> "stream-usage-model");
        registry.add("gateway.routes.stream-done-then-extra-model.provider", () -> "openai");
        registry.add("gateway.routes.stream-done-then-extra-model.upstream-model", () -> "stream-done-then-extra-model");
        registry.add("gateway.routes.stream-missing-done-model.provider", () -> "openai");
        registry.add("gateway.routes.stream-missing-done-model.upstream-model", () -> "stream-missing-done-model");
        registry.add("gateway.routes.stream-partial-then-error-model.provider", () -> "openai");
        registry.add("gateway.routes.stream-partial-then-error-model.upstream-model", () -> "stream-partial-then-error-model");
        registry.add("gateway.routes.x-request-id-model.provider", () -> "openai");
        registry.add("gateway.routes.x-request-id-model.upstream-model", () -> "x-request-id-model");

        // Resilience config
        registry.add("gateway.resilience.retryable-failure-threshold", () -> "2");
        registry.add("gateway.resilience.max-attempts", () -> "2");

        // Rate limit test client (low window)
        registry.add("gateway.clients.rate-limit-key.enabled", () -> "true");
        registry.add("gateway.clients.rate-limit-key.allowed-models", () -> "gpt-4o-mini");
        registry.add("gateway.clients.rate-limit-key.limits.requests-per-window", () -> "3");
        registry.add("gateway.clients.rate-limit-key.limits.window", () -> "1m");

        // Tracing must be enabled for trace store to populate
        registry.add("gateway.tracing.enabled", () -> "true");

        // Auth config for CB recovery test (need admin JWT for alerts/requests routes)
        registry.add("gateway.auth.enabled", () -> "true");
        registry.add("gateway.auth.jwt.secret", () -> "super-secret-key-that-is-at-least-32-chars");
        registry.add("gateway.auth.jwt.access-expiration", () -> "60s");
        registry.add("gateway.auth.users.admin.password", () -> "admin123");
        registry.add("gateway.auth.users.admin.client-id", () -> "demo-client-key");
        registry.add("gateway.auth.users.admin.role", () -> "admin");

        // CB route with NO fallback
        registry.add("gateway.routes.cb-fail-model.provider", () -> "openai");
        registry.add("gateway.routes.cb-fail-model.upstream-model", () -> "cb-fail-model");

        // CB resilience config (small window, short wait)
        registry.add("gateway.resilience.sliding-window-size", () -> "3");
        registry.add("gateway.resilience.wait-duration-in-open-state", () -> "200ms");
        registry.add("gateway.resilience.permitted-number-of-calls-in-half-open-state", () -> "1");
    }

    @Test
    void shouldProxyNonStreamingViaMockUpstream() {
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_mock");
    }

    @Test
    void shouldProxyStreamingViaMockUpstream() {
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", true
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("chunk-1")) throw new AssertionError("missing streaming chunk");
                    if (!body.contains("[DONE]")) throw new AssertionError("missing done marker");
                });
    }

    @Test
    void shouldReturnTimeoutForSlowUpstream() {
        fallbackServerHits.set(0);
        primaryTimeoutRouteHits.set(0);

        String body = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini-timeout",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        if (primaryTimeoutRouteHits.get() != 1) throw new AssertionError("primary timeout route was not hit exactly once");
        if (fallbackServerHits.get() != 1) throw new AssertionError("fallback route was not hit after timeout");
        if (body == null || !body.contains("chatcmpl_fallback")) throw new AssertionError("expected fallback response body but got: " + body);
    }

    @Test
    void shouldNormalizeProviderErrorFromMockUpstream() {
        fallbackServerHits.set(0);
        primaryProviderErrorRouteHits.set(0);

        String body = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini-provider-error",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        if (primaryProviderErrorRouteHits.get() != 1) throw new AssertionError("primary provider-error route was not hit exactly once");
        if (fallbackServerHits.get() != 1) throw new AssertionError("fallback route was not hit after provider error");
        if (body == null || !body.contains("chatcmpl_fallback")) throw new AssertionError("expected fallback response body but got: " + body);
    }

    @Test
    void shouldReturnRateLimitHeaders() {
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches("X-RateLimit-Limit", "\\d+")
                .expectHeader().valueMatches("X-RateLimit-Remaining", "\\d+")
                .expectHeader().valueMatches("X-RateLimit-Reset", "\\d+");
    }

    @Test
    void shouldProxyStreamingWithUsageExtraction() {
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "stream-usage-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", true
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("chunk-1")) throw new AssertionError("missing streaming chunk");
                    if (!body.contains("total_tokens")) throw new AssertionError("missing usage in stream");
                    if (!body.contains("[DONE]")) throw new AssertionError("missing done marker");
                });
    }

    @Test
    void shouldStopStreamAfterTerminalDoneWithoutForwardingPostDoneBusinessData() {
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "stream-done-then-extra-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", true
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("chunk-before-done")) throw new AssertionError("missing pre-DONE business chunk");

                    int doneCount = 0;
                    int idx = 0;
                    while ((idx = body.indexOf("data: [DONE]", idx)) != -1) {
                        doneCount++;
                        idx += "data: [DONE]".length();
                    }
                    if (doneCount != 1) throw new AssertionError("expected exactly one terminal DONE but got " + doneCount);

                    int donePos = body.indexOf("data: [DONE]");
                    String afterDone = body.substring(donePos + "data: [DONE]".length());
                    if (afterDone.contains("chunk-after-done") || afterDone.contains("SHOULD_NOT_APPEAR")) {
                        throw new AssertionError("found business data after terminal DONE");
                    }
                });
    }

    @Test
    void shouldAppendSingleTerminalDoneWhenUpstreamOmitsDoneAndNotEmitBusinessDataAfterIt() {
        String body = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "stream-missing-done-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", true
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        if (body == null || !body.contains("chunk-before-terminal")) {
            throw new AssertionError("missing business chunk before terminal done");
        }

        int doneCount = 0;
        int idx = 0;
        while ((idx = body.indexOf("data: [DONE]", idx)) != -1) {
            doneCount++;
            idx += "data: [DONE]".length();
        }
        if (doneCount != 1) throw new AssertionError("expected exactly one terminal DONE but got " + doneCount);

        int donePos = body.indexOf("data: [DONE]");
        String afterDone = body.substring(donePos + "data: [DONE]".length());
        if (afterDone.contains("\"choices\"") || afterDone.contains("chunk-")) {
            throw new AssertionError("found business data after terminal DONE");
        }
    }

    @Test
    void shouldFallbackOnStreamingFailure() {
        fallbackServerHits.set(0);

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "stream-fail-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", true
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("fallback")) throw new AssertionError("expected fallback stream response");
                    if (!body.contains("[DONE]")) throw new AssertionError("missing done marker");
                });

        if (fallbackServerHits.get() < 1) throw new AssertionError("fallback server was not hit");
    }

    @Test
    void shouldFallbackOnStreamingTimeoutBeforeFirstChunk() {
        fallbackServerHits.set(0);
        primaryStreamTimeoutRouteHits.set(0);

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "stream-timeout-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", true
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    if (!body.contains("fallback")) throw new AssertionError("expected fallback stream response");
                    if (!body.contains("[DONE]")) throw new AssertionError("missing done marker");
                });

        if (primaryStreamTimeoutRouteHits.get() != 1) throw new AssertionError("primary stream timeout route was not hit exactly once");
        if (fallbackServerHits.get() < 1) throw new AssertionError("fallback server was not hit after stream timeout");
    }

    @Test
    void shouldTreatPartialStreamThenUpstreamErrorAsFailureInsteadOfSuccessWithDone() {
        fallbackServerHits.set(0);

        String body = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "stream-partial-then-error-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", true
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        if (body == null || !body.contains("chunk-before-error")) {
            throw new AssertionError("missing pre-error business chunk");
        }
        if (body.contains("data: [DONE]")) {
            throw new AssertionError("stream interrupted by upstream error must not be surfaced as success-with-DONE");
        }
        if (body.toLowerCase().contains("fallback")) {
            throw new AssertionError("fallback stream content must not be returned after first business chunk");
        }
        if (fallbackServerHits.get() != 0) {
            throw new AssertionError("fallback server must not be hit after first business chunk");
        }
    }

    @Test
    void shouldKeepRequestIdVisibleForInterruptedStreamFailureDiagnosis() {
        fallbackServerHits.set(0);
        String requestId = "req-stream-interrupted-001";

        String body = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "stream-partial-then-error-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "stream", true
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", requestId)
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        if (body == null || !body.contains("chunk-before-error")) {
            throw new AssertionError("missing pre-error business chunk");
        }
        if (body.contains("data: [DONE]")) {
            throw new AssertionError("interrupted stream must not be marked as completed");
        }
        if (fallbackServerHits.get() != 0) {
            throw new AssertionError("fallback server must not be hit for interrupted stream after first chunk");
        }
    }

    @Test
    void shouldPropagateXRequestIdToUpstreamAndBackToClient() {
        lastSeenUpstreamRequestId.set(null);
        String requestId = "req-e2e-visible-001";

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "x-request-id-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")}
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", requestId)
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_reqid");

        if (!requestId.equals(lastSeenUpstreamRequestId.get())) {
            throw new AssertionError("upstream did not receive propagated X-Request-Id");
        }
    }

    @Test
    void shouldPassMockScenarioThroughGatewayAndTriggerFallback() {
        fallbackServerHits.set(0);
        primaryScenarioProviderErrorHits.set(0);

        String body = webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini-scenario",
                        "messages", new Object[]{Map.of("role", "user", "content", "hello")},
                        "mock_scenario", "provider-error"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        if (primaryScenarioProviderErrorHits.get() != 1) throw new AssertionError("scenario provider-error route was not hit exactly once");
        if (fallbackServerHits.get() != 1) throw new AssertionError("fallback route was not hit after scenario provider error");
        if (body == null || !body.contains("chatcmpl_fallback")) throw new AssertionError("expected fallback response body but got: " + body);
    }

    @Test
    void shouldEnforceRateLimitWith429() {
        // Uses dedicated rate-limit-key with requests-per-window=3
        for (int i = 0; i < 3; i++) {
            webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer rate-limit-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", "gpt-4o-mini",
                            "messages", new Object[]{Map.of("role", "user", "content", "hello " + i)}
                    ))
                    .exchange()
                    .expectStatus().isOk();
        }

        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer rate-limit-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-4o-mini",
                        "messages", new Object[]{Map.of("role", "user", "content", "should be blocked")}
                ))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.code").isEqualTo("rate_limited");
    }

    @Test
    void shouldOpenCircuitBreakerAfterRepeatedFailures() {
        fallbackServerHits.set(0);
        primaryProviderErrorRouteHits.set(0);

        for (int i = 0; i < 3; i++) {
            webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer demo-client-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", "gpt-4o-mini-provider-error",
                            "messages", new Object[]{Map.of("role", "user", "content", "hello " + i)}
                    ))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.id").isEqualTo("chatcmpl_fallback");
        }

        if (fallbackServerHits.get() != 3) throw new AssertionError("expected 3 fallback hits but got " + fallbackServerHits.get());
        if (primaryProviderErrorRouteHits.get() < 1) throw new AssertionError("primary was never hit");
    }

    @Test
    void shouldCompleteCircuitBreakerRecoveryLoop() throws Exception {
        primaryProviderErrorRouteHits.set(0);
        fallbackServerHits.set(0);

        // Step 1: exhaust CB with 4 failing requests (502 from upstream)
        for (int i = 0; i < 4; i++) {
            webTestClient.post().uri("/v1/chat/completions")
                    .header("Authorization", "Bearer demo-client-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "model", "cb-fail-model",
                            "messages", new Object[]{Map.of("role", "user", "content", "fail " + i)}
                    ))
                    .exchange()
                    .expectStatus().is5xxServerError();
        }

        // Verify primary was hit at least once
        if (primaryProviderErrorRouteHits.get() < 1) throw new AssertionError("primary CB route was never hit");

        // Step 2: next request should fail immediately with 503 circuit_breaker_open (CB open, no fallback)
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "cb-fail-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "cb should be open")}
                ))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("circuit_breaker_open");

        // Step 3: wait for CB half-open window
        Thread.sleep(300);

        // Step 4: make recovery request (upstream returns success for cb_recover)
        primaryProviderErrorRouteHits.set(0);
        webTestClient.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer demo-client-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "cb-fail-model",
                        "messages", new Object[]{Map.of("role", "user", "content", "cb_recover")}
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("chatcmpl_cb_recovered");
    }

}
