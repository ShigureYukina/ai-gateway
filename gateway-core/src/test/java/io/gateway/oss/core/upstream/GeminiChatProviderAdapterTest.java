package io.gateway.oss.core.upstream;

import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.StringDecoder;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiChatProviderAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DisposableServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.disposeNow();
        }
    }

    // ─── 基础属性 ───

    @Test
    void shouldReturnProviderTypeGemini() {
        GeminiChatProviderAdapter adapter = new GeminiChatProviderAdapter(WebClient.builder(), objectMapper);
        assertEquals("gemini", adapter.providerType());
    }

    @Test
    void shouldSupportStreaming() {
        GeminiChatProviderAdapter adapter = new GeminiChatProviderAdapter(WebClient.builder(), objectMapper);
        assertTrue(adapter.supportsStreaming());
    }

    // ─── system message → system_instruction ───

    @Test
    void shouldMapSystemMessageToSystemInstruction() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> request.receive().aggregate().asString().flatMap(body -> {
                            // 验证请求体包含 system_instruction
                            if (!body.contains("\"system_instruction\"")) {
                                return response.status(500)
                                        .sendString(Mono.just("missing system_instruction, body: " + body)).then();
                            }
                            if (!body.contains("\"text\":\"You are a helpful assistant\"")) {
                                return response.status(500)
                                        .sendString(Mono.just("system prompt text missing")).then();
                            }
                            // 验证 contents 不包含 system 消息
                            if (body.contains("\"role\":\"system\"")) {
                                return response.status(500)
                                        .sendString(Mono.just("system message should not appear in contents")).then();
                            }
                            return response.status(200)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just(geminiResponse("Hello!", "STOP", 10, 5)))
                                    .then();
                        })))
                .bindNow();

        GeminiChatProviderAdapter adapter = new GeminiChatProviderAdapter(WebClient.builder(), objectMapper);
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gemini-2.0-flash",
                List.of(
                        new ChatMessage("system", "You are a helpful assistant"),
                        new ChatMessage("user", "Hello")
                ),
                false,
                0.7,
                256
        );

        StepVerifier.create(adapter.complete(request, route("http://localhost:" + mockServer.port())))
                .assertNext(body -> assertEquals("chat.completion", body.get("object")))
                .verifyComplete();
    }

    // ─── assistant → model 角色转换 ───

    @Test
    void shouldMapAssistantRoleToModel() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> request.receive().aggregate().asString().flatMap(body -> {
                            // 验证 assistant 被转换为 model 角色
                            if (!body.contains("\"role\":\"model\"")) {
                                return response.status(500)
                                        .sendString(Mono.just("assistant should be mapped to model, body: " + body)).then();
                            }
                            if (body.contains("\"role\":\"assistant\"")) {
                                return response.status(500)
                                        .sendString(Mono.just("assistant role should not appear in Gemini payload")).then();
                            }
                            // 验证 user 角色保持不变
                            if (!body.contains("\"role\":\"user\"")) {
                                return response.status(500)
                                        .sendString(Mono.just("user role missing")).then();
                            }
                            return response.status(200)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just(geminiResponse("Hi!", "STOP", 8, 3)))
                                    .then();
                        })))
                .bindNow();

        GeminiChatProviderAdapter adapter = new GeminiChatProviderAdapter(WebClient.builder(), objectMapper);
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gemini-2.0-flash",
                List.of(
                        new ChatMessage("user", "Hello"),
                        new ChatMessage("assistant", "Hi there!"),
                        new ChatMessage("user", "How are you?")
                ),
                false,
                null,
                null
        );

        StepVerifier.create(adapter.complete(request, route("http://localhost:" + mockServer.port())))
                .assertNext(body -> assertEquals("chat.completion", body.get("object")))
                .verifyComplete();
    }

    // ─── 响应归一化验证 ───

    @Test
    void shouldNormalizeGeminiResponseToOpenaiShape() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "candidates": [{
                                            "content": {"role": "model", "parts": [{"text": "42"}]},
                                            "finishReason": "STOP"
                                          }],
                                          "usageMetadata": {
                                            "promptTokenCount": 15,
                                            "candidatesTokenCount": 8,
                                            "totalTokenCount": 23
                                          },
                                          "modelVersion": "gemini-2.0-flash-001"
                                        }
                                        """))
                                .then()))
                .bindNow();

        GeminiChatProviderAdapter adapter = new GeminiChatProviderAdapter(WebClient.builder(), objectMapper);
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gemini-2.0-flash",
                List.of(new ChatMessage("user", "What is the answer?")),
                false, null, 256
        );

        StepVerifier.create(adapter.complete(request, route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    // id 格式
                    assertTrue(body.get("id").toString().startsWith("chatcmpl-"), "id should start with chatcmpl-");
                    // object 类型
                    assertEquals("chat.completion", body.get("object"));
                    // created 时间戳
                    assertTrue(body.get("created") instanceof Number, "created should be a number");
                    // model 使用 upstream 返回的 modelVersion
                    assertEquals("gemini-2.0-flash-001", body.get("model"));
                    // choices
                    List<?> choices = (List<?>) body.get("choices");
                    assertEquals(1, choices.size());
                    Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
                    assertEquals(0, firstChoice.get("index"));
                    assertEquals("stop", firstChoice.get("finish_reason"));
                    Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
                    assertEquals("assistant", message.get("role"));
                    assertEquals("42", message.get("content"));
                    // usage
                    Map<String, Object> usage = (Map<String, Object>) body.get("usage");
                    assertEquals(15, usage.get("prompt_tokens"));
                    assertEquals(8, usage.get("completion_tokens"));
                    assertEquals(23, usage.get("total_tokens"));
                })
                .verifyComplete();
    }

    @Test
    void shouldTreatResponseWithoutUsageMetadataAsSuccess() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("""
                                        {
                                          "candidates": [{
                                            "content": {"role": "model", "parts": [{"text": "ok"}]},
                                            "finishReason": "STOP"
                                          }],
                                          "modelVersion": "gemini-2.0-flash-001"
                                        }
                                        """))
                                .then()))
                .bindNow();

        GeminiChatProviderAdapter adapter = createAdapter();

        StepVerifier.create(adapter.complete(request(), route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    assertEquals("chat.completion", body.get("object"));
                    assertEquals("gemini-2.0-flash-001", body.get("model"));

                    List<?> choices = (List<?>) body.get("choices");
                    assertEquals(1, choices.size());
                    Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
                    assertEquals("stop", firstChoice.get("finish_reason"));
                    Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
                    assertEquals("assistant", message.get("role"));
                    assertEquals("ok", message.get("content"));

                    Map<String, Object> usage = (Map<String, Object>) body.get("usage");
                    assertTrue(usage.isEmpty(), "usage should be empty when usageMetadata is missing");
                })
                .verifyComplete();
    }

    // ─── finish reason 映射 ───

    @Test
    void shouldMapFinishReasonStopToStop() {
        mockServer = createMockServer(geminiResponse("done", "STOP", 5, 2));
        GeminiChatProviderAdapter adapter = createAdapter();

        StepVerifier.create(adapter.complete(request(), route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    var choices = (List<?>) body.get("choices");
                    var firstChoice = (Map<?, ?>) choices.get(0);
                    assertEquals("stop", firstChoice.get("finish_reason"));
                })
                .verifyComplete();
    }

    @Test
    void shouldMapFinishReasonMaxTokensToLength() {
        mockServer = createMockServer(geminiResponse("truncated", "MAX_TOKENS", 5, 100));
        GeminiChatProviderAdapter adapter = createAdapter();

        StepVerifier.create(adapter.complete(request(), route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    var choices = (List<?>) body.get("choices");
                    var firstChoice = (Map<?, ?>) choices.get(0);
                    assertEquals("length", firstChoice.get("finish_reason"));
                })
                .verifyComplete();
    }

    @Test
    void shouldMapFinishReasonSafetyToContentFilter() {
        mockServer = createMockServer(geminiResponse("", "SAFETY", 5, 0));
        GeminiChatProviderAdapter adapter = createAdapter();

        StepVerifier.create(adapter.complete(request(), route("http://localhost:" + mockServer.port())))
                .assertNext(body -> {
                    var choices = (List<?>) body.get("choices");
                    var firstChoice = (Map<?, ?>) choices.get(0);
                    assertEquals("content_filter", firstChoice.get("finish_reason"));
                })
                .verifyComplete();
    }

    // ─── model fallback 到 requestedModel ───

    @Test
    void shouldUseRequestedModelWhenModelVersionMissing() {
        String response = """
                {
                  "candidates": [{"content": {"role": "model", "parts": [{"text": "ok"}]}, "finishReason": "STOP"}],
                  "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 2, "totalTokenCount": 7}
                }
                """;
        mockServer = createMockServer(response);
        GeminiChatProviderAdapter adapter = createAdapter();

        StepVerifier.create(adapter.complete(request(), route("http://localhost:" + mockServer.port())))
                .assertNext(body -> assertEquals("gemini-2.0-flash", body.get("model")))
                .verifyComplete();
    }

    // ─── 错误归一化测试 ───

    @Test
    void shouldNormalizeUpstream4xxToGatewayError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> response.status(400)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":{\"code\":400,\"message\":\"bad request\"}}"))
                                .then()))
                .bindNow();

        GeminiChatProviderAdapter adapter = createAdapter();

        StepVerifier.create(adapter.complete(request(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.BAD_REQUEST
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeUpstream5xxToGatewayError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> response.status(500)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":{\"code\":500,\"message\":\"internal error\"}}"))
                                .then()))
                .bindNow();

        GeminiChatProviderAdapter adapter = createAdapter();

        StepVerifier.create(adapter.complete(request(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeUpstream502ToGatewayError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> response.status(502)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"error\":\"bad gateway\"}"))
                                .then()))
                .bindNow();

        GeminiChatProviderAdapter adapter = createAdapter();

        StepVerifier.create(adapter.complete(request(), route("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.BAD_GATEWAY
                        && "upstream_error".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeUpstreamTimeoutToGatewayTimeoutError() {
        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> Mono.delay(Duration.ofMillis(2000)).then(
                                response.status(200)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just(geminiResponse("late", "STOP", 1, 1)))
                                        .then())))
                .bindNow();

        GeminiChatProviderAdapter adapter = createAdapter();

        StepVerifier.create(adapter.complete(request(), routeWithTimeout("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                        && "upstream_timeout".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldNormalizeUpstreamStreamTimeoutToGatewayTimeoutError() {
        String sseLine = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello\"}]},\"finishReason\":\"STOP\"}],\"modelVersion\":\"gemini-2.0-flash-001\"}\n\n";

        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post(
                        "/v1beta/models/gemini-2.0-flash:streamGenerateContent",
                        (request, response) -> Mono.delay(Duration.ofMillis(2000)).then(
                                response.status(200)
                                        .header("Content-Type", "text/event-stream")
                                        .sendString(Mono.just(sseLine))
                                        .then())))
                .bindNow();

        WebClient.Builder streamingBuilder = WebClient.builder()
                .codecs(configurer -> configurer.customCodecs()
                        .register(StringDecoder.allMimeTypes()));
        GeminiChatProviderAdapter adapter = new GeminiChatProviderAdapter(streamingBuilder, objectMapper);

        ChatCompletionsRequest req = new ChatCompletionsRequest(
                "gemini-2.0-flash",
                List.of(new ChatMessage("user", "Hello")),
                true, null, 256
        );

        StepVerifier.create(adapter.stream(req, routeWithTimeout("http://localhost:" + mockServer.port())))
                .expectErrorMatches(error -> error instanceof GatewayException gatewayException
                        && gatewayException.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                        && "upstream_timeout".equals(gatewayException.getCode()))
                .verify();
    }

    @Test
    void shouldRejectUnsupportedMessageRoleInsteadOfSilentlyConverting() {
        GeminiChatProviderAdapter adapter = createAdapter();
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gemini-2.0-flash",
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

    // ─── 流式响应测试 ───
    // 注：bodyToFlux(String.class) 在 text/event-stream 下需要 StringDecoder.allMimeTypes() 才能按行拆分

    @Test
    void shouldHandleStreamingResponse() {
        String sseLine1 = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello\"}]},\"finishReason\":null}],\"modelVersion\":\"gemini-2.0-flash-001\"}\n\n";
        String sseLine2 = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\" World\"}]},\"finishReason\":\"STOP\"}],\"modelVersion\":\"gemini-2.0-flash-001\"}\n\n";

        mockServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.post(
                        "/v1beta/models/gemini-2.0-flash:streamGenerateContent",
                        (request, response) -> response.status(200)
                                .header("Content-Type", "text/event-stream")
                                .sendString(Flux.just(sseLine1, sseLine2))
                                .then()))
                .bindNow();

        // 配置 WebClient 使其能按行解码 text/event-stream 中的 String
        WebClient.Builder streamingBuilder = WebClient.builder()
                .codecs(configurer -> configurer.customCodecs()
                        .register(StringDecoder.allMimeTypes()));
        GeminiChatProviderAdapter adapter = new GeminiChatProviderAdapter(streamingBuilder, objectMapper);

        ChatCompletionsRequest req = new ChatCompletionsRequest(
                "gemini-2.0-flash",
                List.of(new ChatMessage("user", "Hello")),
                true, null, 256
        );

        StepVerifier.create(adapter.stream(req, route("http://localhost:" + mockServer.port())))
                .consumeNextWith(line -> {
                    assertTrue(line.startsWith("data: "), "should start with 'data: ', got: " + line);
                    assertTrue(line.contains("\"object\":\"chat.completion.chunk\""), "should contain chunk object: " + line);
                })
                .consumeNextWith(line -> {
                    assertTrue(line.startsWith("data: "), "should start with 'data: ', got: " + line);
                    assertTrue(line.contains("\"finish_reason\":\"stop\""), "should contain finish_reason: " + line);
                })
                .verifyComplete();
    }

    // ─── 辅助方法 ───

    private String geminiResponse(String text, String finishReason, int promptTokens, int completionTokens) {
        return """
                {
                  "candidates": [{
                    "content": {"role": "model", "parts": [{"text": "%s"}]},
                    "finishReason": "%s"
                  }],
                  "usageMetadata": {
                    "promptTokenCount": %d,
                    "candidatesTokenCount": %d,
                    "totalTokenCount": %d
                  },
                  "modelVersion": "gemini-2.0-flash-001"
                }
                """.formatted(text, finishReason, promptTokens, completionTokens, promptTokens + completionTokens);
    }

    private DisposableServer createMockServer(String responseBody) {
        return HttpServer.create()
                .port(0)
                .route(routes -> routes.post("/v1beta/models/gemini-2.0-flash:generateContent",
                        (request, response) -> response.status(200)
                                .header("Content-Type", "application/json")
                                .sendString(Mono.just(responseBody))
                                .then()))
                .bindNow();
    }

    private GeminiChatProviderAdapter createAdapter() {
        return new GeminiChatProviderAdapter(WebClient.builder(), objectMapper);
    }

    private ChatCompletionsRequest request() {
        return new ChatCompletionsRequest(
                "gemini-2.0-flash",
                List.of(new ChatMessage("user", "Hello")),
                false, null, 256
        );
    }

    private ResolvedRoute route(String baseUrl) {
        return new ResolvedRoute(
                "gemini-2.0-flash",
                "gemini-primary",
                "default-chat",
                "gemini",
                "gemini",
                "gemini-2.0-flash",
                baseUrl,
                "test-key",
                Duration.ofSeconds(2),
                2,
                List.of()
        );
    }

    private ResolvedRoute routeWithTimeout(String baseUrl) {
        return new ResolvedRoute(
                "gemini-2.0-flash",
                "gemini-primary",
                "default-chat",
                "gemini",
                "gemini",
                "gemini-2.0-flash",
                baseUrl,
                "test-key",
                Duration.ofMillis(500),
                2,
                List.of()
        );
    }
}
