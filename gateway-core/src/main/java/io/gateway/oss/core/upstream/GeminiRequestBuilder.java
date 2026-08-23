package io.gateway.oss.core.upstream;

import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.error.GatewayException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GeminiRequestBuilder {

    private GeminiRequestBuilder() {
    }

    public static Map<String, Object> buildPayload(ChatCompletionsRequest request) {
        Map<String, Object> payload = new HashMap<>();

        String systemPrompt = combineSystemMessages(request.messages());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            Map<String, Object> systemInstruction = new HashMap<>();
            systemInstruction.put("parts", List.of(Map.of("text", systemPrompt)));
            payload.put("system_instruction", systemInstruction);
        }

        payload.put("contents", buildContents(request.messages()));

        Map<String, Object> generationConfig = new HashMap<>();
        if (request.maxTokens() != null) {
            generationConfig.put("maxOutputTokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            generationConfig.put("temperature", request.temperature());
        }
        if (request.responseFormat() != null) {
            Object type = request.responseFormat().get("type");
            if ("json_object".equals(type)) {
                generationConfig.put("responseMimeType", "application/json");
            }
        }
        if (!generationConfig.isEmpty()) {
            payload.put("generationConfig", generationConfig);
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            payload.put("tools", buildTools(request.tools()));
        }
        if (request.toolChoice() != null) {
            Object toolConfig = buildToolConfig(request.toolChoice());
            if (toolConfig != null) {
                payload.put("toolConfig", toolConfig);
            }
        }

        return payload;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> buildTools(List<Map<String, Object>> openaiTools) {
        List<Map<String, Object>> declarations = new ArrayList<>();
        for (Map<String, Object> tool : openaiTools) {
            Map<String, Object> func = (Map<String, Object>) tool.get("function");
            if (func == null) {
                continue;
            }
            Map<String, Object> decl = new HashMap<>();
            decl.put("name", func.get("name"));
            if (func.containsKey("description")) {
                decl.put("description", func.get("description"));
            }
            if (func.containsKey("parameters")) {
                decl.put("parameters", func.get("parameters"));
            }
            declarations.add(decl);
        }
        return List.of(Map.of("functionDeclarations", declarations));
    }

    public static Object buildToolConfig(Object openaiToolChoice) {
        Map<String, Object> functionCallingConfig = new HashMap<>();
        if (openaiToolChoice instanceof String text) {
            functionCallingConfig.put("mode", switch (text) {
                case "auto" -> "AUTO";
                case "none" -> "NONE";
                case "required" -> "ANY";
                default -> null;
            });
        } else if (openaiToolChoice instanceof Map<?, ?> toolChoiceMap) {
            Object function = toolChoiceMap.get("function");
            if (function instanceof Map<?, ?> functionMap) {
                functionCallingConfig.put("mode", "ANY");
                String name = (String) functionMap.get("name");
                if (name != null) {
                    functionCallingConfig.put("allowedFunctionNames", List.of(name));
                }
            }
        }
        return functionCallingConfig.isEmpty() ? null : Map.of("functionCallingConfig", functionCallingConfig);
    }

    public static List<Map<String, Object>> buildContents(List<ChatMessage> messages) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage message : messages) {
            String role = message.role();
            if ("system".equals(role)) {
                continue;
            }
            if ("tool".equals(role)) {
                contents.add(buildFunctionResponse(message));
                continue;
            }
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request",
                        "Unsupported message role for gemini provider: " + role);
            }
            String geminiRole = "assistant".equals(role) ? "model" : role;
            contents.add(Map.of(
                    "role", geminiRole,
                    "parts", ContentTranslator.toGeminiParts(message)
            ));
        }
        if (contents.isEmpty()) {
            contents.add(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", ""))
            ));
        }
        return contents;
    }

    public static Map<String, Object> buildFunctionResponse(ChatMessage message) {
        Map<String, Object> part = new HashMap<>();
        Map<String, Object> functionResponse = new HashMap<>();
        functionResponse.put("name", message.name() != null ? message.name() : "");
        functionResponse.put("response", Map.of("result", message.textContent()));
        part.put("functionResponse", functionResponse);
        return Map.of("role", "user", "parts", List.of(part));
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
}
