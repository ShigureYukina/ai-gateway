package io.gateway.oss.core.upstream;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleChatProviderAdapterTest {

    private DisposableServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.disposeNow();
        }
    }

    @Test
    void shouldReturnNormalizedSuccessResponse() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"chatcmpl_test123",
                                          "object":"chat.completion",
                                          "created":1700000000,
                                          "model":"gpt-4o-mini",
                                          "choices":[{"index":0,"message":{"role":"assistant","content":"Hi!"},"finish_reason":"stop"}],
                                          "usage":{"prompt_tokens":10,"completion_tokens":5}
                                        }
                                        """))
                                .then()))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                false,
                0.7,
                128
        );

        StepVerifier.create(adapter.complete(request, route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    assertEquals("chat.completion", body.get("object"));
                    assertEquals("gpt-4o-mini", body.get("model"));
                    assertEquals("chatcmpl_test123", body.get("id"));
                    Object choices = body.get("choices");
                    assertTrue(choices instanceof List<?>);
                    var firstChoice = (Map<?, ?>) ((List<?>) choices).get(0);
                    assertEquals("stop", firstChoice.get("finish_reason"));
                    var message = (Map<?, ?>) firstChoice.get("message");
                    assertEquals("assistant", message.get("role"));
                    assertEquals("Hi!", message.get("content"));
                    var usage = (Map<?, ?>) body.get("usage");
                    assertEquals(10, usage.get("prompt_tokens"));
                    assertEquals(5, usage.get("completion_tokens"));
                    assertEquals(15, usage.get("total_tokens"));
                })
                .verifyComplete();
    }

    @Test
    void shouldEnsureTotalTokensWhenUpstreamOmitsField() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"chatcmpl_nototal",
                                          "object":"chat.completion",
                                          "model":"gpt-4o-mini",
                                          "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                                          "usage":{"prompt_tokens":20,"completion_tokens":8}
                                        }
                                        """))
                                .then()))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    var usage = (Map<?, ?>) body.get("usage");
                    assertEquals(20, usage.get("prompt_tokens"));
                    assertEquals(8, usage.get("completion_tokens"));
                    assertEquals(28, usage.get("total_tokens"));
                })
                .verifyComplete();
    }

    @Test
    void shouldPreserveExistingTotalTokensFromUpstream() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"chatcmpl_withtotal",
                                          "object":"chat.completion",
                                          "model":"gpt-4o-mini",
                                          "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                                          "usage":{"prompt_tokens":12,"completion_tokens":18,"total_tokens":30}
                                        }
                                        """))
                                .then()))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    var usage = (Map<?, ?>) body.get("usage");
                    assertEquals(30, usage.get("total_tokens"));
                })
                .verifyComplete();
    }

    @Test
    void shouldNormalizeUpstream4xxToGatewayError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(400)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":{\"message\":\"bad request\"}}"))
                                .then()))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.BAD_REQUEST
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeUpstream5xxToGatewayError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(500)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":{\"message\":\"internal error\"}}"))
                                .then()))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeUpstream502ToGatewayError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(502)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":\"bad gateway\"}"))
                                .then()))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.BAD_GATEWAY
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeUpstreamTimeoutToGatewayTimeoutError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        Mono.delay(Duration.ofMillis(2000)).then(
                                response.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"id\":\"late\"}"))
                                        .then())))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());

        StepVerifier.create(adapter.complete(nonStreamingRequest(), routeWithTimeout("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                        && "upstream_timeout".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeUpstreamStreamTimeoutToGatewayTimeoutError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        Mono.delay(Duration.ofMillis(2000)).then(
                                response.status(200)
                                        .header("Content-Type", "text/event-stream")
                                        .sendString(Mono.just("data: {\"id\":\"late\"}\n\n"))
                                        .then())))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                true,
                null,
                128
        );

        StepVerifier.create(adapter.stream(request, routeWithTimeout("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                        && "upstream_timeout".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeUpstream429StreamToGatewayError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(429)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":{\"message\":\"rate limited\"}}"))
                                .then()))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                true,
                null,
                128
        );

        StepVerifier.create(adapter.stream(request, route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.TOO_MANY_REQUESTS
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldSupportStreamingByDefault() {
        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());
        assertTrue(adapter.supportsStreaming());
    }

    @Test
    void shouldUseRequestedModelAsFallbackWhenUpstreamOmitsModel() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "id":"chatcmpl_nomodel",
                                          "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                                          "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}
                                        }
                                        """))
                                .then()))
                .bindNow();

        OpenAiCompatibleChatProviderAdapter adapter = new OpenAiCompatibleChatProviderAdapter(WebClient.builder());

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    assertEquals("gpt-4o-mini", body.get("model"));
                    assertEquals("chat.completion", body.get("object"));
                })
                .verifyComplete();
    }

    private ChatCompletionsRequest nonStreamingRequest() {
        return new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                false,
                null,
                256
        );
    }

    private ResolvedRoute route(String baseUrl) {
        return new ResolvedRoute(
                "gpt-4o-mini",
                "openai-primary",
                "default-chat",
                "openai",
                "openai-compatible",
                "gpt-4o-mini",
                baseUrl,
                "test-key",
                Duration.ofSeconds(10),
                2,
                List.of()
        );
    }

    private ResolvedRoute routeWithTimeout(String baseUrl) {
        return new ResolvedRoute(
                "gpt-4o-mini",
                "openai-primary",
                "default-chat",
                "openai",
                "openai-compatible",
                "gpt-4o-mini",
                baseUrl,
                "test-key",
                Duration.ofMillis(500),
                2,
                List.of()
        );
    }
}
