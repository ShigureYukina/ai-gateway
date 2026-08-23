package io.gateway.oss.core.upstream;

import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 请求体与请求头构建工具。
 */
public final class AnthropicRequestBuilder {

    private AnthropicRequestBuilder() {
    }

    public static Map<String, Object> buildMessagesPayload(ChatCompletionsRequest request,
                                                           ResolvedRoute route,
                                                           int defaultMaxTokens) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", route.upstreamModel());
        payload.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : defaultMaxTokens);

        if (request.temperature() != null) {
            payload.put("temperature", request.temperature());
        }

        String systemPrompt = combineSystemMessages(request.messages());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            payload.put("system", systemPrompt);
        }

        payload.put("messages", anthropicMessages(request.messages()));

        if (request.tools() != null && !request.tools().isEmpty()) {
            payload.put("tools", toAnthropicTools(request.tools()));
        }
        if (request.toolChoice() != null) {
            Object toolChoice = toAnthropicToolChoice(request.toolChoice());
            if (toolChoice != null) {
                payload.put("tool_choice", toolChoice);
            }
        }

        return payload;
    }

    public static Map<String, Object> buildStreamingMessagesPayload(ChatCompletionsRequest request,
                                                                    ResolvedRoute route,
                                                                    int defaultMaxTokens) {
        Map<String, Object> payload = buildMessagesPayload(request, route, defaultMaxTokens);
        payload.put("stream", true);
        return payload;
    }

    public static void applyRequestHeaders(HttpHeaders headers, String apiKey, ChatCompletionsRequest request) {
        headers.set("x-api-key", apiKey);
        Object requestId = request.extras().get(ChatCompletionsRequest.GATEWAY_REQUEST_ID_EXTRA);
        if (requestId instanceof String text && !text.isBlank()) {
            headers.set("X-Request-Id", text);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> toAnthropicTools(List<Map<String, Object>> openaiTools) {
        List<Map<String, Object>> anthropicTools = new ArrayList<>();
        for (Map<String, Object> tool : openaiTools) {
            Map<String, Object> func = (Map<String, Object>) tool.get("function");
            if (func == null) {
                continue;
            }
            Map<String, Object> anthropicTool = new HashMap<>();
            anthropicTool.put("name", func.get("name"));
            if (func.containsKey("description")) {
                anthropicTool.put("description", func.get("description"));
            }
            if (func.containsKey("parameters")) {
                anthropicTool.put("input_schema", func.get("parameters"));
            }
            anthropicTools.add(anthropicTool);
        }
        return anthropicTools;
    }

    public static Object toAnthropicToolChoice(Object openaiToolChoice) {
        if (openaiToolChoice instanceof String text) {
            return switch (text) {
                case "auto" -> Map.of("type", "auto");
                case "none" -> Map.of("type", "none");
                case "required" -> Map.of("type", "any");
                default -> null;
            };
        }
        if (openaiToolChoice instanceof Map<?, ?> map) {
            Object function = map.get("function");
            if (function instanceof Map<?, ?> functionMap) {
                String name = (String) functionMap.get("name");
                if (name != null) {
                    return Map.of("type", "tool", "name", name);
                }
            }
        }
        return null;
    }

    public static String combineSystemMessages(List<ChatMessage> messages) {
        List<String> systemContents = new ArrayList<>();
        for (ChatMessage message : messages) {
            if ("system".equals(message.role())) {
                systemContents.add(message.textContent());
            }
        }
        if (systemContents.isEmpty()) {
            return null;
        }
        return String.join("\n\n", systemContents);
    }

    public static List<Map<String, Object>> anthropicMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> converted = new ArrayList<>();
        for (ChatMessage message : messages) {
            String role = message.role();
            if ("system".equals(role)) {
                continue;
            }
            if ("tool".equals(role)) {
                converted.add(toAnthropicToolResultMessage(message));
                continue;
            }
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request",
                        "Unsupported message role for anthropic provider: " + role);
            }
            converted.add(Map.of(
                    "role", role,
                    "content", ContentTranslator.toAnthropicContent(message)
            ));
        }
        if (converted.isEmpty()) {
            converted.add(Map.of(
                    "role", "user",
                    "content", List.of(Map.of("type", "text", "text", ""))
            ));
        }
        return converted;
    }

    public static Map<String, Object> toAnthropicToolResultMessage(ChatMessage message) {
        Map<String, Object> anthropicMessage = new HashMap<>();
        anthropicMessage.put("role", "user");
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> result = new HashMap<>();
        result.put("type", "tool_result");
        result.put("tool_use_id", message.toolCallId() != null ? message.toolCallId() : "");
        result.put("content", message.textContent());
        blocks.add(result);
        anthropicMessage.put("content", blocks);
        return anthropicMessage;
    }
}
