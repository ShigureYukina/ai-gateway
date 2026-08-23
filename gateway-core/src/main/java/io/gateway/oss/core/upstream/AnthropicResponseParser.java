package io.gateway.oss.core.upstream;

import io.gateway.oss.core.contract.routing.ResolvedRoute;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anthropic 响应解析工具。
 */
public final class AnthropicResponseParser {

    private AnthropicResponseParser() {
    }

    public static Map<String, Object> normalizeToGatewayShape(Map<String, Object> anthropicBody, ResolvedRoute route) {
        Object contentNode = anthropicBody.get("content");
        String assistantText = extractAssistantText(contentNode);
        List<Map<String, Object>> toolCalls = extractToolCalls(contentNode);
        String finishReason = mapFinishReason(asString(anthropicBody.get("stop_reason")),
                toolCalls != null && !toolCalls.isEmpty());
        Integer promptTokens = asInteger(readNested(anthropicBody, "usage", "input_tokens"));
        Integer completionTokens = asInteger(readNested(anthropicBody, "usage", "output_tokens"));

        Map<String, Object> usage = buildUsage(promptTokens, completionTokens);

        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        if (assistantText != null && !assistantText.isEmpty()) {
            message.put("content", assistantText);
        } else {
            message.put("content", null);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            message.put("tool_calls", toolCalls);
        }

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);

        Map<String, Object> normalized = new HashMap<>();
        normalized.put("id", asString(anthropicBody.get("id")) != null
                ? asString(anthropicBody.get("id"))
                : "chatcmpl_" + UUID.randomUUID());
        normalized.put("object", "chat.completion");
        normalized.put("created", Instant.now().getEpochSecond());
        normalized.put("model", route.requestedModel());
        normalized.put("choices", List.of(choice));
        normalized.put("usage", usage);
        return normalized;
    }

    @SuppressWarnings("unchecked")
    public static String parseStreamEvent(String json,
                                          String syntheticId,
                                          long created,
                                          String requestedModel,
                                          ObjectMapper objectMapper) {
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return null;
        }

        if (data == null) {
            return null;
        }

        String type = asString(data.get("type"));
        if ("content_block_delta".equals(type)) {
            return parseContentBlockDelta(data, syntheticId, created, requestedModel, objectMapper);
        }
        if ("message_delta".equals(type)) {
            return parseMessageDelta(data, syntheticId, created, requestedModel, objectMapper);
        }
        if ("message_start".equals(type)) {
            return parseMessageStart(data, syntheticId, created, requestedModel, objectMapper);
        }

        return null;
    }

    public static List<Map<String, Object>> extractToolCalls(Object contentNode) {
        if (!(contentNode instanceof List<?> contentList)) {
            return null;
        }
        List<Map<String, Object>> toolCalls = null;
        int index = 0;
        for (Object block : contentList) {
            if (!(block instanceof Map<?, ?> map)) {
                continue;
            }
            if (!"tool_use".equals(map.get("type"))) {
                continue;
            }
            if (toolCalls == null) {
                toolCalls = new ArrayList<>();
            }
            Map<String, Object> toolCall = new HashMap<>();
            toolCall.put("id", map.get("id"));
            toolCall.put("type", "function");
            Map<String, Object> function = new HashMap<>();
            function.put("name", map.get("name"));
            function.put("arguments", map.get("input") != null ? asString(map.get("input")) : "{}");
            toolCall.put("function", function);
            toolCall.put("index", index++);
            toolCalls.add(toolCall);
        }
        return toolCalls;
    }

    public static String extractAssistantText(Object contentNode) {
        if (!(contentNode instanceof List<?> contentList)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object block : contentList) {
            if (!(block instanceof Map<?, ?> map)) {
                continue;
            }
            if (!"text".equals(map.get("type"))) {
                continue;
            }
            Object text = map.get("text");
            if (text != null) {
                if (!builder.isEmpty()) {
                    builder.append("\n");
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    public static Map<String, Object> buildUsage(Integer promptTokens, Integer completionTokens) {
        Map<String, Object> usage = new HashMap<>();
        if (promptTokens != null) {
            usage.put("prompt_tokens", promptTokens);
        }
        if (completionTokens != null) {
            usage.put("completion_tokens", completionTokens);
        }
        if (promptTokens != null && completionTokens != null) {
            usage.put("total_tokens", promptTokens + completionTokens);
        }
        return usage;
    }

    private static String parseContentBlockDelta(Map<String, Object> data,
                                                 String syntheticId,
                                                 long created,
                                                 String requestedModel,
                                                 ObjectMapper objectMapper) {
        String text = "";
        Object delta = data.get("delta");
        if (delta instanceof Map<?, ?> deltaMap) {
            Object textValue = deltaMap.get("text");
            if (textValue != null) {
                text = textValue.toString();
            }
        }

        Map<String, Object> deltaObject = new HashMap<>();
        deltaObject.put("content", text);

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("delta", deltaObject);

        Map<String, Object> chunk = baseChunk(syntheticId, created, requestedModel, choice);
        return toSseData(chunk, objectMapper);
    }

    private static String parseMessageDelta(Map<String, Object> data,
                                            String syntheticId,
                                            long created,
                                            String requestedModel,
                                            ObjectMapper objectMapper) {
        String finishReason = null;
        Integer outputTokens = null;

        Object delta = data.get("delta");
        if (delta instanceof Map<?, ?> deltaMap) {
            Object stopReason = deltaMap.get("stop_reason");
            if (stopReason != null) {
                finishReason = mapFinishReason(stopReason.toString(), false);
            }
        }
        Object usage = data.get("usage");
        if (usage instanceof Map<?, ?> usageMap) {
            Object outputTokenValue = usageMap.get("output_tokens");
            if (outputTokenValue instanceof Number number) {
                outputTokens = number.intValue();
            }
        }

        Map<String, Object> deltaObject = new HashMap<>();
        deltaObject.put("content", "");

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("delta", deltaObject);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }

        Map<String, Object> chunk = baseChunk(syntheticId, created, requestedModel, choice);
        if (outputTokens != null) {
            Map<String, Object> usageObject = new HashMap<>();
            usageObject.put("completion_tokens", outputTokens);
            usageObject.put("total_tokens", outputTokens);
            chunk.put("usage", usageObject);
        }
        return toSseData(chunk, objectMapper);
    }

    private static String parseMessageStart(Map<String, Object> data,
                                            String syntheticId,
                                            long created,
                                            String requestedModel,
                                            ObjectMapper objectMapper) {
        Object message = data.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            return null;
        }
        Object usage = messageMap.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) {
            return null;
        }
        Object inputTokenValue = usageMap.get("input_tokens");
        if (!(inputTokenValue instanceof Number number)) {
            return null;
        }

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("delta", Map.of("content", ""));

        Map<String, Object> chunk = baseChunk(syntheticId, created, requestedModel, choice);
        Map<String, Object> usageObject = new HashMap<>();
        usageObject.put("prompt_tokens", number.intValue());
        chunk.put("usage", usageObject);
        return toSseData(chunk, objectMapper);
    }

    private static Map<String, Object> baseChunk(String syntheticId,
                                                 long created,
                                                 String requestedModel,
                                                 Map<String, Object> choice) {
        Map<String, Object> chunk = new HashMap<>();
        chunk.put("id", syntheticId);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", requestedModel);
        chunk.put("choices", List.of(choice));
        return chunk;
    }

    private static String toSseData(Map<String, Object> chunk, ObjectMapper objectMapper) {
        try {
            return "data: " + objectMapper.writeValueAsString(chunk) + "\n\n";
        } catch (JsonProcessingException e) {
            return "data: {}\n\n";
        }
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

    private static String mapFinishReason(String anthropicReason, boolean hasToolCalls) {
        if (hasToolCalls) {
            return "tool_calls";
        }
        if ("max_tokens".equals(anthropicReason)) {
            return "length";
        }
        if ("end_turn".equals(anthropicReason) || "stop_sequence".equals(anthropicReason)) {
            return "stop";
        }
        return anthropicReason == null ? "stop" : anthropicReason;
    }
}
