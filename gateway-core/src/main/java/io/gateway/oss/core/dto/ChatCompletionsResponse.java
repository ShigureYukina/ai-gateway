package io.gateway.oss.core.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "OpenAI-compatible chat completion response (non-streaming)")
public record ChatCompletionsResponse(
        @Schema(example = "chatcmpl-abc123")
        String id,
        @Schema(example = "chat.completion")
        String object,
        @Schema(example = "1710000000")
        Long created,
        @Schema(example = "gpt-4o-mini")
        String model,
        @ArraySchema(schema = @Schema(implementation = Choice.class))
        List<Choice> choices,
        Usage usage
) {
    public record Choice(
            @Schema(example = "0")
            Integer index,
            Message message,
            @Schema(example = "stop")
            String finish_reason
    ) {
    }

    public record Message(
            @Schema(example = "assistant")
            String role,
            @Schema(example = "Hello! How can I help?")
            String content
    ) {
    }

    public record Usage(
            @Schema(example = "12")
            Integer prompt_tokens,
            @Schema(example = "34")
            Integer completion_tokens,
            @Schema(example = "46")
            Integer total_tokens
    ) {
    }
}
