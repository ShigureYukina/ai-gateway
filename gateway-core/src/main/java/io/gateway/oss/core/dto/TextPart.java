package io.gateway.oss.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A text content part in a multi-modal message content array.
 *
 * <p>Corresponds to OpenAI's {@code {"type": "text", "text": "..."}} format.</p>
 */
public record TextPart(
    @JsonProperty("type") String type,
    String text
) implements ContentPart {
    public TextPart(String text) {
        this("text", text);
    }
}
