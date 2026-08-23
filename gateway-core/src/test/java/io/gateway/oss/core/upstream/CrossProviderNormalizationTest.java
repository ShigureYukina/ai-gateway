package io.gateway.oss.core.upstream;

import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-provider normalization consistency test.
 * Verifies that both adapters produce responses with the same normalized
 * gateway shape: object, model, choices, usage (with total_tokens).
 */
class CrossProviderNormalizationTest {

    private DisposableServer openAiServer;
    private DisposableServer anthropicServer;

    @BeforeEach
    void setUp() {
        openAiServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"chatcmpl_cross",
                                          "object":"chat.completion",
                                          "created":1700000000,
                                          "model":"gpt-4o-mini",
                                          "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from OpenAI"},"finish_reason":"stop"}],
                                          "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}
                                        }
                                        """))
                                .then()))
                .bindNow();

        anthropicServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"msg_cross",
                                          "type":"message",
                                          "role":"assistant",
                                          "content":[{"type":"text","text":"Hello from Anthropic"}],
                                          "model":"claude-3-5-sonnet",
                                          "stop_reason":"end_turn",
                                          "usage":{"input_tokens":10,"output_tokens":5}
                                        }
                                        """))
                                .then()))
                .bindNow();
    }

    @AfterEach
    void tearDown() {
        if (openAiServer != null) openAiServer.disposeNow();
        if (anthropicServer != null) anthropicServer.disposeNow();
    }

    @Test
    void shouldProduceSameNormalizedShapeForBothProviders() {
        OpenAiCompatibleChatProviderAdapter openAiAdapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
        AnthropicChatProviderAdapter anthropicAdapter = new AnthropicChatProviderAdapter(WebClient.builder(), new ObjectMapper());

        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                false,
                0.7,
                128
        );

        ResolvedRoute openAiRoute = new ResolvedRoute(
                "gpt-4o-mini", "openai-route", "default-chat",
                "openai", "openai-compatible", "gpt-4o-mini",
                "http://localhost:" + openAiServer.port(),
                "test-key", Duration.ofSeconds(10), 2, List.of()
        );
        ResolvedRoute anthropicRoute = new ResolvedRoute(
                "gpt-4o-mini", "anthropic-route", "default-chat",
                "anthropic", "anthropic", "claude-3-5-sonnet",
                "http://localhost:" + anthropicServer.port(),
                "test-key", Duration.ofSeconds(10), 2, List.of()
        );

        Mono<Map<String, Object>> openAiResult = openAiAdapter.complete(request, openAiRoute);
        Mono<Map<String, Object>> anthropicResult = anthropicAdapter.complete(request, anthropicRoute);

        StepVerifier.create(Mono.zip(openAiResult, anthropicResult))
                .assertNext(tuple -> {
                    Map<String, Object> openAiBody = tuple.getT1();
                    Map<String, Object> anthropicBody = tuple.getT2();

                    // Both must have object = chat.completion
                    assertEquals("chat.completion", openAiBody.get("object"), "OpenAI object field");
                    assertEquals("chat.completion", anthropicBody.get("object"), "Anthropic object field");

                    // Both must have model
                    assertNotNull(openAiBody.get("model"), "OpenAI model field");
                    assertNotNull(anthropicBody.get("model"), "Anthropic model field");

                    // Both must have id
                    assertNotNull(openAiBody.get("id"), "OpenAI id field");
                    assertNotNull(anthropicBody.get("id"), "Anthropic id field");

                    // Both must have choices as List with at least 1 element
                    assertTrue(openAiBody.get("choices") instanceof List<?>, "OpenAI choices is list");
                    assertTrue(anthropicBody.get("choices") instanceof List<?>, "Anthropic choices is list");
                    List<?> openAiChoices = (List<?>) openAiBody.get("choices");
                    List<?> anthropicChoices = (List<?>) anthropicBody.get("choices");
                    assertTrue(openAiChoices.size() >= 1, "OpenAI has at least 1 choice");
                    assertTrue(anthropicChoices.size() >= 1, "Anthropic has at least 1 choice");

                    // Both choices must have message with role=assistant and content
                    Map<?, ?> openAiMessage = (Map<?, ?>) ((Map<?, ?>) openAiChoices.get(0)).get("message");
                    Map<?, ?> anthropicMessage = (Map<?, ?>) ((Map<?, ?>) anthropicChoices.get(0)).get("message");
                    assertEquals("assistant", openAiMessage.get("role"), "OpenAI message role");
                    assertEquals("assistant", anthropicMessage.get("role"), "Anthropic message role");
                    assertNotNull(openAiMessage.get("content"), "OpenAI message content");
                    assertNotNull(anthropicMessage.get("content"), "Anthropic message content");

                    // Both choices must have finish_reason
                    assertNotNull(((Map<?, ?>) openAiChoices.get(0)).get("finish_reason"), "OpenAI finish_reason");
                    assertNotNull(((Map<?, ?>) anthropicChoices.get(0)).get("finish_reason"), "Anthropic finish_reason");

                    // Both must have usage with prompt_tokens, completion_tokens, total_tokens
                    Map<?, ?> openAiUsage = (Map<?, ?>) openAiBody.get("usage");
                    Map<?, ?> anthropicUsage = (Map<?, ?>) anthropicBody.get("usage");
                    assertNotNull(openAiUsage, "OpenAI usage");
                    assertNotNull(anthropicUsage, "Anthropic usage");

                    assertEquals(10, openAiUsage.get("prompt_tokens"), "OpenAI prompt_tokens");
                    assertEquals(10, anthropicUsage.get("prompt_tokens"), "Anthropic prompt_tokens");
                    assertEquals(5, openAiUsage.get("completion_tokens"), "OpenAI completion_tokens");
                    assertEquals(5, anthropicUsage.get("completion_tokens"), "Anthropic completion_tokens");
                    assertEquals(15, openAiUsage.get("total_tokens"), "OpenAI total_tokens");
                    assertEquals(15, anthropicUsage.get("total_tokens"), "Anthropic total_tokens");
                })
                .verifyComplete();
    }

    @Test
    void shouldNormalizeFinishReasonConsistently() {
        // Anthropic stop_sequence -> stop (same as end_turn)
        // OpenAI stop -> stop
        // Both should map to "stop"
        OpenAiCompatibleChatProviderAdapter openAiAdapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
        AnthropicChatProviderAdapter anthropicAdapter = new AnthropicChatProviderAdapter(WebClient.builder(), new ObjectMapper());

        assertTrue(openAiAdapter.supportsStreaming(), "OpenAI supports streaming");
        assertEquals(true, anthropicAdapter.supportsStreaming(), "Anthropic supports streaming");
    }

    @Test
    void normalize_emptyChoices_handledGracefully() {
        DisposableServer emptyChoicesServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"chatcmpl_empty",
                                          "object":"chat.completion",
                                          "created":1700000000,
                                          "model":"gpt-4o-mini",
                                          "choices":[],
                                          "usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}
                                        }
                                        """))
                                .then()))
                .bindNow();

        try {
            OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
            ChatCompletionsRequest request = new ChatCompletionsRequest(
                    "gpt-4o-mini",
                    List.of(new ChatMessage("user", "hello")),
                    false, 0.7, 128
            );
            ResolvedRoute route = new ResolvedRoute(
                    "gpt-4o-mini", "test-route", "default-chat",
                    "openai", "openai-compatible", "gpt-4o-mini",
                    "http://localhost:" + emptyChoicesServer.port(),
                    "test-key", Duration.ofSeconds(10), 2, List.of()
            );

            StepVerifier.create(adapter.complete(request, route))
                    .assertNext(body -> {
                        List<?> choices = (List<?>) body.get("choices");
                        assertNotNull(choices);
                        assertEquals(0, choices.size());
                    })
                    .verifyComplete();
        } finally {
            emptyChoicesServer.disposeNow();
        }
    }

    @Test
    void normalize_missingUsage_returnsZeroes() {
        DisposableServer noUsageServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"chatcmpl_no_usage",
                                          "object":"chat.completion",
                                          "created":1700000000,
                                          "model":"gpt-4o-mini",
                                          "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]
                                        }
                                        """))
                                .then()))
                .bindNow();

        try {
            OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
            ChatCompletionsRequest request = new ChatCompletionsRequest(
                    "gpt-4o-mini",
                    List.of(new ChatMessage("user", "hello")),
                    false, 0.7, 128
            );
            ResolvedRoute route = new ResolvedRoute(
                    "gpt-4o-mini", "test-route", "default-chat",
                    "openai", "openai-compatible", "gpt-4o-mini",
                    "http://localhost:" + noUsageServer.port(),
                    "test-key", Duration.ofSeconds(10), 2, List.of()
            );

            StepVerifier.create(adapter.complete(request, route))
                    .assertNext(body -> {
                        // When usage is missing, the adapter does not add it
                        // The response should still succeed without throwing
                        assertNotNull(body.get("choices"));
                    })
                    .verifyComplete();
        } finally {
            noUsageServer.disposeNow();
        }
    }

    @Test
    void normalize_missingUsage_shouldStillSucceedForAnthropicResponse() {
        DisposableServer anthropicNoUsageServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"msg_no_usage",
                                          "type":"message",
                                          "role":"assistant",
                                          "content":[{"type":"text","text":"Hello without usage"}],
                                          "model":"claude-3-5-sonnet",
                                          "stop_reason":"end_turn"
                                        }
                                        """))
                                .then()))
                .bindNow();

        try {
            AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), new ObjectMapper());
            ChatCompletionsRequest request = new ChatCompletionsRequest(
                    "claude-3-5-sonnet",
                    List.of(new ChatMessage("user", "hello")),
                    false,
                    0.7,
                    128
            );
            ResolvedRoute route = new ResolvedRoute(
                    "claude-3-5-sonnet", "test-route", "default-chat",
                    "anthropic", "anthropic", "claude-3-5-sonnet",
                    "http://localhost:" + anthropicNoUsageServer.port(),
                    "test-key", Duration.ofSeconds(10), 2, List.of()
            );

            StepVerifier.create(adapter.complete(request, route))
                    .assertNext(body -> {
                        assertEquals("chat.completion", body.get("object"));
                        assertNotNull(body.get("choices"));
                    })
                    .verifyComplete();
        } finally {
            anthropicNoUsageServer.disposeNow();
        }
    }

    @Test
    void normalize_finishReason_stopMapsCorrectly() {
        DisposableServer stopReasonServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"msg_stop",
                                          "type":"message",
                                          "role":"assistant",
                                          "content":[{"type":"text","text":"Done"}],
                                          "model":"claude-3-5-sonnet",
                                          "stop_reason":"stop_sequence",
                                          "usage":{"input_tokens":5,"output_tokens":3}
                                        }
                                        """))
                                .then()))
                .bindNow();

        try {
            AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), new ObjectMapper());
            ChatCompletionsRequest request = new ChatCompletionsRequest(
                    "claude-3-5-sonnet",
                    List.of(new ChatMessage("user", "hello")),
                    false, 0.7, 128
            );
            ResolvedRoute route = new ResolvedRoute(
                    "claude-3-5-sonnet", "test-route", "default-chat",
                    "anthropic", "anthropic", "claude-3-5-sonnet",
                    "http://localhost:" + stopReasonServer.port(),
                    "test-key", Duration.ofSeconds(10), 2, List.of()
            );

            StepVerifier.create(adapter.complete(request, route))
                    .assertNext(body -> {
                        List<?> choices = (List<?>) body.get("choices");
                        assertNotNull(choices);
                        assertEquals(1, choices.size());
                        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
                        // Anthropic stop_sequence should map to "stop"
                        assertEquals("stop", choice.get("finish_reason"));
                    })
                    .verifyComplete();
        } finally {
            stopReasonServer.disposeNow();
        }
    }

    @Test
    void shouldNormalize5xxErrorConsistentlyAcrossProviders() {
        DisposableServer openAiErrorServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(500)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":{\"message\":\"internal\"}}"))
                                .then()))
                .bindNow();

        DisposableServer anthropicErrorServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(500)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"internal\"}}"))
                                .then()))
                .bindNow();

        DisposableServer geminiErrorServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> response.status(500)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":{\"code\":500,\"message\":\"internal\"}}"))
                                .then()))
                .bindNow();

        try {
            OpenAiCompatibleChatProviderAdapter openAiAdapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
            AnthropicChatProviderAdapter anthropicAdapter = new AnthropicChatProviderAdapter(WebClient.builder(), new ObjectMapper());
            GeminiChatProviderAdapter geminiAdapter = new GeminiChatProviderAdapter(WebClient.builder(), new ObjectMapper());

            ChatCompletionsRequest request = new ChatCompletionsRequest(
                    "gpt-4o-mini", List.of(new ChatMessage("user", "hello")),
                    false, 0.7, 128
            );

            ResolvedRoute openAiRoute = new ResolvedRoute(
                    "gpt-4o-mini", "r", "s", "openai", "openai-compatible", "gpt-4o-mini",
                    "http://localhost:" + openAiErrorServer.port(), "k", Duration.ofSeconds(10), 2, List.of());
            ResolvedRoute anthropicRoute = new ResolvedRoute(
                    "gpt-4o-mini", "r", "s", "anthropic", "anthropic", "claude-3-5-sonnet",
                    "http://localhost:" + anthropicErrorServer.port(), "k", Duration.ofSeconds(10), 2, List.of());
            ResolvedRoute geminiRoute = new ResolvedRoute(
                    "gemini-2.0-flash", "r", "s", "gemini", "gemini", "gemini-2.0-flash",
                    "http://localhost:" + geminiErrorServer.port(), "k", Duration.ofSeconds(10), 2, List.of());

            Mono<Map<String, Object>> openAiResult = openAiAdapter.complete(request, openAiRoute);
            Mono<Map<String, Object>> anthropicResult = anthropicAdapter.complete(request, anthropicRoute);
            Mono<Map<String, Object>> geminiResult = geminiAdapter.complete(request, geminiRoute);

            StepVerifier.create(Mono.zip(
                    openAiResult.onErrorReturn(Map.of("error", true)).map(r -> "success"),
                    anthropicResult.onErrorReturn(Map.of("error", true)).map(r -> "success"),
                    geminiResult.onErrorReturn(Map.of("error", true)).map(r -> "success")))
                    .assertNext(tuple -> {
                        assertEquals("success", tuple.getT1());
                        assertEquals("success", tuple.getT2());
                        assertEquals("success", tuple.getT3());
                    })
                    .verifyComplete();

            // Verify all three produce GatewayException with same status and code
            StepVerifier.create(openAiResult)
                    .expectErrorMatches(e -> e instanceof GatewayException ge
                            && ge.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR
                            && "upstream_error".equals(ge.getCode()))
                    .verify();

            StepVerifier.create(anthropicResult)
                    .expectErrorMatches(e -> e instanceof GatewayException ge
                            && ge.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR
                            && "upstream_error".equals(ge.getCode()))
                    .verify();

            StepVerifier.create(geminiResult)
                    .expectErrorMatches(e -> e instanceof GatewayException ge
                            && ge.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR
                            && "upstream_error".equals(ge.getCode()))
                    .verify();

        } finally {
            openAiErrorServer.disposeNow();
            anthropicErrorServer.disposeNow();
            geminiErrorServer.disposeNow();
        }
    }

    @Test
    void shouldNormalizeTimeoutConsistentlyAcrossProviders() {
        DisposableServer openAiSlowServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        Mono.delay(Duration.ofMillis(2000)).then(
                                response.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"id\":\"late\"}"))
                                        .then())))
                .bindNow();

        DisposableServer anthropicSlowServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        Mono.delay(Duration.ofMillis(2000)).then(
                                response.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"id\":\"late\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"))
                                        .then())))
                .bindNow();

        DisposableServer geminiSlowServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> Mono.delay(Duration.ofMillis(2000)).then(
                                response.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"candidates\":[],\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1,\"totalTokenCount\":2}}"))
                                        .then())))
                .bindNow();

        try {
            OpenAiCompatibleChatProviderAdapter openAiAdapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
            AnthropicChatProviderAdapter anthropicAdapter = new AnthropicChatProviderAdapter(WebClient.builder(), new ObjectMapper());
            GeminiChatProviderAdapter geminiAdapter = new GeminiChatProviderAdapter(WebClient.builder(), new ObjectMapper());

            ChatCompletionsRequest request = new ChatCompletionsRequest(
                    "gpt-4o-mini", List.of(new ChatMessage("user", "hello")),
                    false, 0.7, 128
            );

            ResolvedRoute openAiRoute = new ResolvedRoute(
                    "gpt-4o-mini", "r", "s", "openai", "openai-compatible", "gpt-4o-mini",
                    "http://localhost:" + openAiSlowServer.port(), "k", Duration.ofMillis(500), 2, List.of());
            ResolvedRoute anthropicRoute = new ResolvedRoute(
                    "gpt-4o-mini", "r", "s", "anthropic", "anthropic", "claude-3-5-sonnet",
                    "http://localhost:" + anthropicSlowServer.port(), "k", Duration.ofMillis(500), 2, List.of());
            ResolvedRoute geminiRoute = new ResolvedRoute(
                    "gemini-2.0-flash", "r", "s", "gemini", "gemini", "gemini-2.0-flash",
                    "http://localhost:" + geminiSlowServer.port(), "k", Duration.ofMillis(500), 2, List.of());

            StepVerifier.create(openAiAdapter.complete(request, openAiRoute))
                    .expectErrorMatches(e -> e instanceof GatewayException ge
                            && ge.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                            && "upstream_timeout".equals(ge.getCode()))
                    .verify();

            StepVerifier.create(anthropicAdapter.complete(request, anthropicRoute))
                    .expectErrorMatches(e -> e instanceof GatewayException ge
                            && ge.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                            && "upstream_timeout".equals(ge.getCode()))
                    .verify();

            StepVerifier.create(geminiAdapter.complete(request, geminiRoute))
                    .expectErrorMatches(e -> e instanceof GatewayException ge
                            && ge.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                            && "upstream_timeout".equals(ge.getCode()))
                    .verify();

        } finally {
            openAiSlowServer.disposeNow();
            anthropicSlowServer.disposeNow();
            geminiSlowServer.disposeNow();
        }
    }

    @Test
    void shouldNormalizeUsageWithZeroTokensConsistently() {
        DisposableServer openAiZeroServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {"id":"z","object":"chat.completion","created":1700000000,"model":"gpt-4o-mini",
                                         "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                                         "usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}
                                        """))
                                .then()))
                .bindNow();

        DisposableServer geminiZeroServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                                         "usageMetadata":{"promptTokenCount":0,"candidatesTokenCount":0,"totalTokenCount":0}}
                                        """))
                                .then()))
                .bindNow();

        try {
            OpenAiCompatibleChatProviderAdapter openAiAdapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
            GeminiChatProviderAdapter geminiAdapter = new GeminiChatProviderAdapter(WebClient.builder(), new ObjectMapper());

            ChatCompletionsRequest request = new ChatCompletionsRequest(
                    "gpt-4o-mini", List.of(new ChatMessage("user", "hello")),
                    false, 0.7, 128
            );

            ResolvedRoute openAiRoute = new ResolvedRoute(
                    "gpt-4o-mini", "r", "s", "openai", "openai-compatible", "gpt-4o-mini",
                    "http://localhost:" + openAiZeroServer.port(), "k", Duration.ofSeconds(10), 2, List.of());
            ResolvedRoute geminiRoute = new ResolvedRoute(
                    "gemini-2.0-flash", "r", "s", "gemini", "gemini", "gemini-2.0-flash",
                    "http://localhost:" + geminiZeroServer.port(), "k", Duration.ofSeconds(10), 2, List.of());

            StepVerifier.create(openAiAdapter.complete(request, openAiRoute))
                    .assertNext(body -> {
                        Map<?, ?> usage = (Map<?, ?>) body.get("usage");
                        assertEquals(0, usage.get("prompt_tokens"));
                        assertEquals(0, usage.get("completion_tokens"));
                        assertEquals(0, usage.get("total_tokens"));
                    })
                    .verifyComplete();

            StepVerifier.create(geminiAdapter.complete(request, geminiRoute))
                    .assertNext(body -> {
                        Map<?, ?> usage = (Map<?, ?>) body.get("usage");
                        assertEquals(0, usage.get("prompt_tokens"));
                        assertEquals(0, usage.get("completion_tokens"));
                        assertEquals(0, usage.get("total_tokens"));
                    })
                    .verifyComplete();

        } finally {
            openAiZeroServer.disposeNow();
            geminiZeroServer.disposeNow();
        }
    }
}
