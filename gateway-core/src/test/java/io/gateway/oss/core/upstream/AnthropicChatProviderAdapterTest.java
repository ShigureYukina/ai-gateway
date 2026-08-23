package io.gateway.oss.core.upstream;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicChatProviderAdapterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private DisposableServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.disposeNow();
        }
    }

    @Test
    void shouldMapNonStreamingRequestAndNormalizeResponse() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        request.receive().aggregate().asString().flatMap(body -> {
                            if (!body.contains("\"model\":\"claude-3-5-sonnet\"")) {
                                return response.status(500).sendString(Mono.just("missing model")).then();
                            }
                            if (!body.contains("\"max_tokens\":256")) {
                                return response.status(500).sendString(Mono.just("missing max_tokens")).then();
                            }
                            if (!body.contains("\"system\":\"You are helpful\"")) {
                                return response.status(500).sendString(Mono.just("missing system prompt")).then();
                            }
                            if (!body.contains("\"role\":\"user\"")) {
                                return response.status(500).sendString(Mono.just("missing user role")).then();
                            }
                            if (!body.contains("\"role\":\"assistant\"")) {
                                return response.status(500).sendString(Mono.just("missing assistant role")).then();
                            }
                            return response.status(200)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just("""
                                            {
                                              "id":"msg_123",
                                              "type":"message",
                                              "role":"assistant",
                                              "content":[
                                                {"type":"text","text":"Hi there"},
                                                {"type":"text","text":"How can I help?"}
                                              ],
                                              "model":"claude-3-5-sonnet",
                                              "stop_reason":"end_turn",
                                              "usage":{"input_tokens":12,"output_tokens":18}
                                            }
                                            """))
                                    .then();
                        })))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(
                        new ChatMessage("system", "You are helpful"),
                        new ChatMessage("user", "hello"),
                        new ChatMessage("assistant", "hi")
                ),
                false,
                0.3d,
                256
        );

        StepVerifier.create(adapter.complete(request, route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    assertEquals("chat.completion", body.get("object"));
                    assertEquals("gpt-4o-mini", body.get("model"));
                    assertEquals("msg_123", body.get("id"));
                    Object choices = body.get("choices");
                    assertTrue(choices instanceof List<?>);
                    var firstChoice = (java.util.Map<?, ?>) ((List<?>) choices).get(0);
                    assertEquals("stop", firstChoice.get("finish_reason"));
                    var message = (java.util.Map<?, ?>) firstChoice.get("message");
                    assertEquals("assistant", message.get("role"));
                    assertEquals("Hi there\nHow can I help?", message.get("content"));
                    var usage = (java.util.Map<?, ?>) body.get("usage");
                    assertEquals(12, usage.get("prompt_tokens"));
                    assertEquals(18, usage.get("completion_tokens"));
                    assertEquals(30, usage.get("total_tokens"));
                })
                .verifyComplete();
    }

    @Test
    void shouldNormalizeAnthropicError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(502)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":\"bad gateway\"}"))
                                .then()))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.BAD_GATEWAY
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldStreamAnthropicSseAndConvertToOpenAiChunks() {
        // Send Anthropic SSE events - the SSE codec strips event/data framing
        String msgStart = "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_999\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-3-5-sonnet\",\"stop_reason\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}";
        String blockStart = "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}";
        String delta1 = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}";
        String delta2 = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}";
        String blockStop = "{\"type\":\"content_block_stop\",\"index\":0}";
        String msgDelta = "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}";
        String msgStop = "{\"type\":\"message_stop\"}";

        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "text/event-stream")
                                .sendString(Flux.just(
                                        "event: message_start\ndata: " + msgStart + "\n\n",
                                        "event: content_block_start\ndata: " + blockStart + "\n\n",
                                        "event: content_block_delta\ndata: " + delta1 + "\n\n",
                                        "event: content_block_delta\ndata: " + delta2 + "\n\n",
                                        "event: content_block_stop\ndata: " + blockStop + "\n\n",
                                        "event: message_delta\ndata: " + msgDelta + "\n\n",
                                        "event: message_stop\ndata: " + msgStop + "\n\n"))
                                .then()))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);

        StepVerifier.create(adapter.stream(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .assertNext(chunk -> {
                    assertTrue(chunk.startsWith("data: "), "expected data prefix, got: " + chunk);
                    assertTrue(chunk.contains("\"chat.completion.chunk\""), "expected chunk object, got: " + chunk);
                    assertTrue(chunk.contains("\"prompt_tokens\":10"), "expected prompt_tokens, got: " + chunk);
                })
                .assertNext(chunk -> {
                    assertTrue(chunk.contains("\"Hello\""), "expected Hello, got: " + chunk);
                })
                .assertNext(chunk -> {
                    assertTrue(chunk.contains("\" world\""), "expected ' world', got: " + chunk);
                })
                .assertNext(chunk -> {
                    assertTrue(chunk.contains("\"finish_reason\":\"stop\""), "expected finish_reason, got: " + chunk);
                    assertTrue(chunk.contains("\"completion_tokens\":5"), "expected completion_tokens, got: " + chunk);
                })
                .verifyComplete();
    }

    @Test
    void shouldIgnoreInvalidAnthropicStreamEventAndContinueParsing() {
        String msgStart = "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_999\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-3-5-sonnet\",\"stop_reason\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}";
        String delta = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}";
        String msgDelta = "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}";

        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "text/event-stream")
                                .sendString(Flux.just(
                                        "event: message_start\ndata: " + msgStart + "\n\n",
                                        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\"\n\n",
                                        "event: content_block_delta\ndata: " + delta + "\n\n",
                                        "event: message_delta\ndata: " + msgDelta + "\n\n"))
                                .then()))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);

        StepVerifier.create(adapter.stream(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .assertNext(chunk -> {
                    assertTrue(chunk.contains("\"prompt_tokens\":10"), "expected prompt_tokens, got: " + chunk);
                })
                .assertNext(chunk -> {
                    assertTrue(chunk.contains("\"Hello\""), "expected Hello chunk, got: " + chunk);
                })
                .assertNext(chunk -> {
                    assertTrue(chunk.contains("\"finish_reason\":\"stop\""), "expected finish_reason, got: " + chunk);
                    assertTrue(chunk.contains("\"completion_tokens\":5"), "expected completion_tokens, got: " + chunk);
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectUnsupportedMessageRoleInsteadOfSilentlyConverting() {
        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(
                        new ChatMessage("function", "call"),
                        new ChatMessage("user", "hello")
                ),
                false,
                null,
                128
        );

        GatewayException exception = assertThrows(GatewayException.class,
                () -> adapter.complete(request, route("http://localhost:18080")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("invalid_request", exception.getCode());
    }

    @Test
    void shouldNormalizeAnthropic4xxToUpstreamError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(400)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad request\"}}"))
                                .then()))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.BAD_REQUEST
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeAnthropic429ToUpstreamError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(429)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate limited\"}}"))
                                .then()))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.TOO_MANY_REQUESTS
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeAnthropic500ToUpstreamError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(500)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"internal error\"}}"))
                                .then()))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeAnthropicTimeoutToGatewayTimeout() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        Mono.delay(Duration.ofMillis(2000)).then(
                                response.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"id\":\"late\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"late\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"))
                                        .then())))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);

        StepVerifier.create(adapter.complete(nonStreamingRequest(), routeWithTimeout("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                        && "upstream_timeout".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldUseDefaultMaxTokensWhenOmittedFromRequest() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        request.receive().aggregate().asString().flatMap(body -> {
                            if (!body.contains("\"max_tokens\":1024")) {
                                return response.status(500).sendString(Mono.just("expected default max_tokens=1024, got: " + body)).then();
                            }
                            return response.status(200)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just("""
                                            {"id":"msg_default","type":"message","role":"assistant","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":3}}
                                            """))
                                    .then();
                        })))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                false,
                null,
                null
        );

        StepVerifier.create(adapter.complete(request, route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    assertEquals("chat.completion", body.get("object"));
                    var usage = (java.util.Map<?, ?>) body.get("usage");
                    assertEquals(5, usage.get("prompt_tokens"));
                    assertEquals(3, usage.get("completion_tokens"));
                    assertEquals(8, usage.get("total_tokens"));
                })
                .verifyComplete();
    }

    @Test
    void shouldMapMaxTokensFinishReasonToLength() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1/messages", (request, response) ->
                        response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {"id":"msg_max","type":"message","role":"assistant","content":[{"type":"text","text":"truncated"}],"stop_reason":"max_tokens","usage":{"input_tokens":10,"output_tokens":100}}
                                        """))
                                .then()))
                .bindNow();

        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);

        StepVerifier.create(adapter.complete(nonStreamingRequest(), route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    var choices = (java.util.List<?>) body.get("choices");
                    var firstChoice = (java.util.Map<?, ?>) choices.get(0);
                    assertEquals("length", firstChoice.get("finish_reason"));
                })
                .verifyComplete();
    }

    @Test
    void shouldSupportStreaming() {
        AnthropicChatProviderAdapter adapter = new AnthropicChatProviderAdapter(WebClient.builder(), OBJECT_MAPPER);
        assertEquals(true, adapter.supportsStreaming());
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
                "anthropic-primary",
                "default-chat",
                "anthropic",
                "anthropic",
                "claude-3-5-sonnet",
                baseUrl,
                "test-key",
                Duration.ofSeconds(2),
                2,
                List.of()
        );
    }

    private ResolvedRoute routeWithTimeout(String baseUrl) {
        return new ResolvedRoute(
                "gpt-4o-mini",
                "anthropic-primary",
                "default-chat",
                "anthropic",
                "anthropic",
                "claude-3-5-sonnet",
                baseUrl,
                "test-key",
                Duration.ofMillis(500),
                2,
                List.of()
        );
    }
}
