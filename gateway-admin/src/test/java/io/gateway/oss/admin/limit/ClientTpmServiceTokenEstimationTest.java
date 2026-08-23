package io.gateway.oss.admin.limit;

import io.gateway.oss.core.dto.ChatCompletionsRequest;
import io.gateway.oss.core.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTpmServiceTokenEstimationTest {

    private final ClientTpmService service = new ClientTpmService(new InMemoryClientTpmStore());

    @Test
    void shouldEstimateTokensUsingTiktokenForTextInput() {
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "Hello, how are you?")),
                false,
                0.7,
                100
        );

        long estimated = service.estimateTokens(request);

        // "Hello, how are you?" is ~6 tokens in cl100k_base + 100 maxTokens = ~106
        assertTrue(estimated > 100, "Expected > 100 but got " + estimated);
        assertTrue(estimated < 120, "Expected < 120 but got " + estimated);
    }

    @Test
    void shouldIncludeMaxTokensInEstimate() {
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hi")),
                false,
                0.7,
                500
        );

        long estimated = service.estimateTokens(request);

        // "hi" is ~1 token + 500 maxTokens = ~501
        assertTrue(estimated >= 500, "Expected >= 500 but got " + estimated);
        assertTrue(estimated < 510, "Expected < 510 but got " + estimated);
    }

    @Test
    void shouldUseFallbackWhenMaxTokensMissing() {
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", "hello")),
                false,
                0.7,
                null
        );

        long estimated = service.estimateTokens(request);

        // "hello" ~1 token + 32 fallback = ~33
        assertTrue(estimated >= 32, "Expected >= 32 but got " + estimated);
        assertTrue(estimated < 40, "Expected < 40 but got " + estimated);
    }

    @Test
    void shouldHandleMultipleMessages() {
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(
                        new ChatMessage("system", "You are a helpful assistant."),
                        new ChatMessage("user", "What is the weather today?")
                ),
                false,
                0.7,
                100
        );

        long estimated = service.estimateTokens(request);

        // Multiple messages should produce more tokens than single
        assertTrue(estimated > 100, "Expected > 100 but got " + estimated);
    }

    @Test
    void shouldReturnFallbackForNullRequest() {
        long estimated = service.estimateTokens(null);
        assertEquals(32L, estimated);
    }

    @Test
    void shouldReturnFallbackForEmptyMessages() {
        ChatCompletionsRequest request = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(),
                false,
                0.7,
                100
        );

        long estimated = service.estimateTokens(request);

        // Empty messages = 0 input + 100 maxTokens = 100
        assertEquals(100L, estimated);
    }

    @Test
    void shouldHandleLongerTextProportionally() {
        String shortText = "Hello";
        String longText = "Hello world! This is a longer message that should produce more tokens when encoded by the tiktoken tokenizer. Let me add some more text to make it clearly longer.";

        ChatCompletionsRequest shortReq = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", shortText)),
                false, 0.7, 100
        );
        ChatCompletionsRequest longReq = new ChatCompletionsRequest(
                "gpt-4o-mini",
                List.of(new ChatMessage("user", longText)),
                false, 0.7, 100
        );

        long shortEstimate = service.estimateTokens(shortReq);
        long longEstimate = service.estimateTokens(longReq);

        assertTrue(longEstimate > shortEstimate,
                "Long text should produce more tokens: short=" + shortEstimate + " long=" + longEstimate);
    }
}
