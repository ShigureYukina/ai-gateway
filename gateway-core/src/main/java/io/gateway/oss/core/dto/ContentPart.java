package io.gateway.oss.core.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A part of a multi-modal chat message content (OpenAI content array format).
 *
 * <p>OpenAI SDK sends content as either a plain string (text-only) or an array
 * of ContentPart objects (multi-modal). Each part has a "type" discriminator.</p>
 *
 * <pre>{@code
 * // Text-only:
 * {"role": "user", "content": "Hello"}
 *
 * // Multi-modal (OpenAI SDK format):
 * {"role": "user", "content": [
 *   {"type": "text", "text": "What's in this image?"},
 *   {"type": "image_url", "image_url": {"url": "https://...", "detail": "auto"}}
 * ]}
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextPart.class, name = "text"),
    @JsonSubTypes.Type(value = ImagePart.class, name = "image_url")
})
public sealed interface ContentPart permits TextPart, ImagePart {

    /** The discriminator type (e.g. "text", "image_url"). */
    String type();
}
