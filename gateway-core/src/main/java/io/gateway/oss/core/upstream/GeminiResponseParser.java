package io.gateway.oss.core.upstream;

import io.gateway.oss.core.error.GatewayException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GeminiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiResponseParser.class);

    private GeminiResponseParser() {
    }

    public static Map<String, Object> normalizeToGatewayShape(Map<String, Object> geminiBody, String requestedModel) {
        String assistantText = "";
        String finishReasonRaw = null;
        String role = "assistant";
        List<Map<String, Object>> toolCalls = null;

        Object candidatesRaw = geminiBody.get("candidates");
        if (candidatesRaw instanceof List<?> candidates && !candidates.isEmpty()) {
            Object candidate = candidates.get(0);
            if (candidate instanceof Map<?, ?> candidateMap) {
                Object contentRaw = candidateMap.get("content");
                if (contentRaw instanceof Map<?, ?> contentMap) {
                    Object partsRaw = contentMap.get("parts");
                    if (partsRaw instanceof List<?> parts && !parts.isEmpty()) {
                        StringBuilder textBuilder = new StringBuilder();
                        int toolCallIndex = 0;
                        for (Object part : parts) {
                            if (!(part instanceof Map<?, ?> partMap)) {
                                continue;
                            }
                            Object text = partMap.get("text");
                            if (text != null) {
                                if (!textBuilder.isEmpty()) {
                                    textBuilder.append("\n");
                                }
                                textBuilder.append(text);
                            }
                            Object functionCall = partMap.get("functionCall");
                            if (functionCall instanceof Map<?, ?> functionCallMap) {
                                if (toolCalls == null) {
                                    toolCalls = new ArrayList<>();
                                }
                                Map<String, Object> toolCall = new HashMap<>();
                                toolCall.put("id", "call_" + UUID.randomUUID());
                                toolCall.put("type", "function");
                                Map<String, Object> function = new HashMap<>();
                                function.put("name", functionCallMap.get("name"));
                                function.put("arguments", functionCallMap.get("args") != null
                                        ? asString(functionCallMap.get("args"))
                                        : "{}");
                                toolCall.put("function", function);
                                toolCall.put("index", toolCallIndex++);
                                toolCalls.add(toolCall);
                            }
                        }
                        assistantText = textBuilder.toString();
                    }
                    Object contentRole = contentMap.get("role");
                    if ("model".equals(contentRole)) {
                        role = "assistant";
                    }
                }
                Object finishReason = candidateMap.get("finishReason");
                finishReasonRaw = finishReason != null ? finishReason.toString() : null;
            }
        }

        String normalizedFinishReason = mapFinishReason(finishReasonRaw, toolCalls != null && !toolCalls.isEmpty());
        Map<String, Object> usage = extractUsage(geminiBody);

        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", assistantText.isEmpty() ? null : assistantText);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            message.put("tool_calls", toolCalls);
        }

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", normalizedFinishReason);

        String model = asString(geminiBody.get("modelVersion"));
        if (model == null) {
            model = requestedModel;
        }

        Map<String, Object> normalized = new HashMap<>();
        normalized.put("id", "chatcmpl-" + UUID.randomUUID());
        normalized.put("object", "chat.completion");
        normalized.put("created", Instant.now().getEpochSecond());
        normalized.put("model", model);
        normalized.put("choices", List.of(choice));
        normalized.put("usage", usage);
        return normalized;
    }

    public static Map<String, Object> parseStreamChunk(String json, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("failed_to_parse_gemini_stream_chunk", e);
            return Map.of();
        }
    }

    public static String toOpenAiStreamChunk(Map<String, Object> geminiChunk,
                                              String syntheticId,
                                              long created,
                                              String requestedModel,
                                              ObjectMapper objectMapper) {
        String text = "";
        String finishReason = null;
        List<Map<String, Object>> toolCalls = null;

        Object candidatesRaw = geminiChunk.get("candidates");
        if (candidatesRaw instanceof List<?> candidates && !candidates.isEmpty()) {
            Object candidate = candidates.get(0);
            if (candidate instanceof Map<?, ?> candidateMap) {
                Object contentRaw = candidateMap.get("content");
                if (contentRaw instanceof Map<?, ?> contentMap) {
                    Object partsRaw = contentMap.get("parts");
                    if (partsRaw instanceof List<?> parts && !parts.isEmpty()) {
                        Object part = parts.get(0);
                        if (part instanceof Map<?, ?> partMap) {
                            Object textValue = partMap.get("text");
                            if (textValue != null) {
                                text = textValue.toString();
                            }
                            Object functionCall = partMap.get("functionCall");
                            if (functionCall instanceof Map<?, ?> functionCallMap) {
                                toolCalls = new ArrayList<>();
                                Map<String, Object> toolCall = new HashMap<>();
                                toolCall.put("id", "call_" + UUID.randomUUID());
                                toolCall.put("type", "function");
                                Map<String, Object> function = new HashMap<>();
                                function.put("name", functionCallMap.get("name"));
                                function.put("arguments", functionCallMap.get("args") != null
                                        ? asString(functionCallMap.get("args"))
                                        : "{}");
                                toolCall.put("function", function);
                                toolCall.put("index", 0);
                                toolCalls.add(toolCall);
                            }
                        }
                    }
                }
                Object rawFinishReason = candidateMap.get("finishReason");
                if (rawFinishReason != null) {
                    finishReason = mapFinishReason(rawFinishReason.toString(), toolCalls != null && !toolCalls.isEmpty());
                }
            }
        }

        Map<String, Object> delta = new HashMap<>();
        delta.put("content", text);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            delta.put("tool_calls", toolCalls);
        }

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }

        String model = asString(geminiChunk.get("modelVersion"));
        if (model == null) {
            model = requestedModel;
        }

        Map<String, Object> chunk = new HashMap<>();
        chunk.put("id", syntheticId);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));

        try {
            return "data: " + objectMapper.writeValueAsString(chunk) + "\n\n";
        } catch (JsonProcessingException e) {
            log.warn("failed_to_serialize_openai_stream_chunk", e);
            return "data: {}\n\n";
        }
    }

    public static Map<String, Object> extractUsage(Map<String, Object> geminiBody) {
        Integer promptTokens = asInteger(readNested(geminiBody, "usageMetadata", "promptTokenCount"));
        Integer completionTokens = asInteger(readNested(geminiBody, "usageMetadata", "candidatesTokenCount"));
        Integer totalTokens = asInteger(readNested(geminiBody, "usageMetadata", "totalTokenCount"));

        Map<String, Object> usage = new HashMap<>();
        if (promptTokens != null) {
            usage.put("prompt_tokens", promptTokens);
        }
        if (completionTokens != null) {
            usage.put("completion_tokens", completionTokens);
        }
        if (totalTokens != null) {
            usage.put("total_tokens", totalTokens);
        } else if (promptTokens != null && completionTokens != null) {
            usage.put("total_tokens", promptTokens + completionTokens);
        }
        return usage;
    }

    public static String extractCandidateText(Map<String, Object> geminiBody) {
        Object candidatesRaw = geminiBody.get("candidates");
        if (!(candidatesRaw instanceof List<?> candidates) || candidates.isEmpty()) {
            return "";
        }
        Object candidate = candidates.get(0);
        if (!(candidate instanceof Map<?, ?> candidateMap)) {
            return "";
        }
        Object contentRaw = candidateMap.get("content");
        if (!(contentRaw instanceof Map<?, ?> contentMap)) {
            return "";
        }
        Object partsRaw = contentMap.get("parts");
        if (!(partsRaw instanceof List<?> parts) || parts.isEmpty()) {
            return "";
        }

        StringBuilder textBuilder = new StringBuilder();
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> partMap)) {
                continue;
            }
            Object text = partMap.get("text");
            if (text == null) {
                continue;
            }
            if (!textBuilder.isEmpty()) {
                textBuilder.append("\n");
            }
            textBuilder.append(text);
        }
        return textBuilder.toString();
    }

    public static String mapFinishReason(String geminiReason, boolean hasToolCalls) {
        if (hasToolCalls) {
            return "tool_calls";
        }
        if (geminiReason == null) {
            return null;
        }
        if ("STOP".equals(geminiReason)) {
            return "stop";
        }
        if ("MAX_TOKENS".equals(geminiReason)) {
            return "length";
        }
        if ("SAFETY".equals(geminiReason) || "RECITATION".equals(geminiReason) || "OTHER".equals(geminiReason)) {
            return "content_filter";
        }
        return "stop";
    }

    public static GatewayException normalizeUpstreamError(org.springframework.http.HttpStatusCode statusCode, String body) {
        String message = body != null && !body.isEmpty()
                ? "Upstream provider error: " + body
                : "Upstream provider error";
        return new GatewayException(toStatus(statusCode), "upstream_error", message);
    }

    private static Object readNested(Map<String, Object> source, String objectKey, String nestedKey) {
        Object object = source.get(objectKey);
        if (!(object instanceof Map<?, ?> nestedMap)) {
            return null;
        }
        return nestedMap.get(nestedKey);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static HttpStatus toStatus(org.springframework.http.HttpStatusCode statusCode) {
        return HttpStatus.resolve(statusCode.value()) == null ? HttpStatus.BAD_GATEWAY : HttpStatus.valueOf(statusCode.value());
    }
}
