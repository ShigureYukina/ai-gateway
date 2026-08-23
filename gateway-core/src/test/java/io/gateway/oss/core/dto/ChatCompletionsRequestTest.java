package io.gateway.oss.core.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCompletionsRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void constructorShouldSetAllFieldsCorrectly() {
        List<ChatMessage> messages = List.of(new ChatMessage("user", "hello"));
        List<Map<String, Object>> tools = List.of(Map.of("type", "function"));
        Map<String, Object> responseFormat = Map.of("type", "json_object");
        Map<String, Object> extras = Map.of("custom", "value");

        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                messages,
                true,
                0.7,
                256,
                tools,
                "auto",
                responseFormat,
                extras
        );

        assertEquals("gpt-4o-mini", request.model());
        assertEquals(messages, request.messages());
        assertEquals(Boolean.TRUE, request.stream());
        assertEquals(0.7, request.temperature());
        assertEquals(256, request.maxTokens());
        assertEquals(tools, request.tools());
        assertEquals("auto", request.toolChoice());
        assertEquals(responseFormat, request.responseFormat());
        assertEquals(extras, request.extras());
    }

    @Test
    void accessorMethodsShouldReturnConfiguredValues() {
        List<ChatMessage> messages = List.of(new ChatMessage("user", "hello"));
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "test-model", messages, false, 0.3, 128
        );

        assertEquals("test-model", request.model());
        assertEquals(messages, request.messages());
        assertEquals(Boolean.FALSE, request.stream());
        assertFalse(request.streamEnabled());
    }

    @Test
    void streamEnabledShouldReturnTrueOnlyWhenStreamIsTrue() {
        ChatCompletionsRequest enabled = new ChatCompletionsRequest("m1", List.of(), true, null, null);
        ChatCompletionsRequest disabled = new ChatCompletionsRequest("m1", List.of(), false, null, null);
        ChatCompletionsRequest unset = new ChatCompletionsRequest("m1", List.of(), null, null, null);

        assertTrue(enabled.streamEnabled());
        assertFalse(disabled.streamEnabled());
        assertFalse(unset.streamEnabled());
    }

    @Test
    void toMapShouldIncludeAllSetFields() {
        List<ChatMessage> messages = List.of(new ChatMessage("user", "hello"));
        List<Map<String, Object>> tools = List.of(Map.of("type", "function"));
        Map<String, Object> responseFormat = Map.of("type", "json_schema");

        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                messages,
                true,
                0.9,
                512,
                tools,
                Map.of("type", "function"),
                responseFormat,
                null
        );

        Map<String, Object> map = request.toMap();

        assertEquals("gpt-4o-mini", map.get("model"));
        assertEquals(messages, map.get("messages"));
        assertEquals(true, map.get("stream"));
        assertEquals(0.9, map.get("temperature"));
        assertEquals(512, map.get("max_tokens"));
        assertEquals(tools, map.get("tools"));
        assertEquals(Map.of("type", "function"), map.get("tool_choice"));
        assertEquals(responseFormat, map.get("response_format"));
    }

    @Test
    void toMapShouldIncludeExtras() {
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                true,
                null,
                null,
                null,
                null,
                null,
                Map.of("trace_id", "req-1", "custom_flag", true)
        );

        Map<String, Object> map = request.toMap();

        assertEquals("req-1", map.get("trace_id"));
        assertEquals(true, map.get("custom_flag"));
        assertEquals("gpt-4o-mini", map.get("model"));
    }

    @Test
    void extrasShouldReturnConfiguredExtraFields() {
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("x-extra", 123)
        );

        assertEquals(Map.of("x-extra", 123), request.extras());
    }

    @Test
    void jsonAnySetterShouldAddToExtras() {
        ChatCompletionsRequest request = new ChatCompletionsRequest("gpt-4o-mini", List.of(), null, null, null);

        request.setExtra("dynamic", "value");

        assertEquals("value", request.extras().get("dynamic"));
    }

    @Test
    void jsonDeserializationShouldPopulateExtrasViaJsonAnySetter() throws Exception {
        String json = """
                {
                  "model": "gpt-4o-mini",
                  "messages": [{"role": "user", "content": "hello"}],
                  "stream": true,
                  "custom_field": "custom-value"
                }
                """;

        ChatCompletionsRequest request = objectMapper.readValue(json, ChatCompletionsRequest.class);

        assertEquals("gpt-4o-mini", request.model());
        assertEquals(1, request.messages().size());
        assertTrue(request.streamEnabled());
        assertEquals("custom-value", request.extras().get("custom_field"));
    }

    @Test
    void validationAnnotationsShouldBePresentOnAccessorMethods() throws Exception {
        Method modelMethod = ChatCompletionsRequest.class.getDeclaredMethod("model");
        Method messagesMethod = ChatCompletionsRequest.class.getDeclaredMethod("messages");

        assertNotNull(modelMethod.getAnnotation(NotBlank.class));
        assertNotNull(messagesMethod.getAnnotation(NotEmpty.class));
    }
}
