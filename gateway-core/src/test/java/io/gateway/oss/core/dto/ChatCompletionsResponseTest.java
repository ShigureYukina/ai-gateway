package io.gateway.oss.core.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatCompletionsResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void constructorShouldSetAllFields() {
        ChatCompletionsResponse.Choice choice = new ChatCompletionsResponse.Choice(
                0,
                new ChatCompletionsResponse.Message("assistant", "Hello"),
                "stop"
        );
        ChatCompletionsResponse.Usage usage = new ChatCompletionsResponse.Usage(12, 34, 46);

        ChatCompletionsResponse response = new ChatCompletionsResponse(
                "chatcmpl-1",
                "chat.completion",
                1710000000L,
                "gpt-4o-mini",
                List.of(choice),
                usage
        );

        assertEquals("chatcmpl-1", response.id());
        assertEquals("chat.completion", response.object());
        assertEquals(1710000000L, response.created());
        assertEquals("gpt-4o-mini", response.model());
        assertEquals(List.of(choice), response.choices());
        assertEquals(usage, response.usage());
    }

    @Test
    void nestedRecordsShouldExposeValues() {
        ChatCompletionsResponse.Message message = new ChatCompletionsResponse.Message("assistant", "Hi there");
        ChatCompletionsResponse.Choice choice = new ChatCompletionsResponse.Choice(1, message, "length");
        ChatCompletionsResponse.Usage usage = new ChatCompletionsResponse.Usage(10, 20, 30);

        assertEquals("assistant", message.role());
        assertEquals("Hi there", message.content());
        assertEquals(1, choice.index());
        assertEquals(message, choice.message());
        assertEquals("length", choice.finish_reason());
        assertEquals(10, usage.prompt_tokens());
        assertEquals(20, usage.completion_tokens());
        assertEquals(30, usage.total_tokens());
    }

    @Test
    void shouldSerializeAndDeserializeWithObjectMapper() throws Exception {
        ChatCompletionsResponse response = new ChatCompletionsResponse(
                "chatcmpl-1",
                "chat.completion",
                1710000000L,
                "gpt-4o-mini",
                List.of(new ChatCompletionsResponse.Choice(
                        0,
                        new ChatCompletionsResponse.Message("assistant", "Hello"),
                        "stop"
                )),
                new ChatCompletionsResponse.Usage(12, 34, 46)
        );

        String json = objectMapper.writeValueAsString(response);
        ChatCompletionsResponse restored = objectMapper.readValue(json, ChatCompletionsResponse.class);

        assertEquals(response, restored);
    }
}
