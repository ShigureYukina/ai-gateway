package io.gateway.oss.core.upstream;

import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.dto.ContentPart;
import io.gateway.oss.core.dto.ImagePart;
import io.gateway.oss.core.dto.TextPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates between OpenAI-compatible content formats and provider-native formats.
 *
 * <p>This is the core of the protocol translation layer. It converts the gateway's
 * internal {@link ChatMessage} representation (OpenAI format) into each provider's
 * native request format, and vice versa.</p>
 *
 * <p>Supported provider formats:</p>
 * <ul>
 *   <li><b>Anthropic:</b> {@code content: [{type: "text", text: "..."}, {type: "image", source: {type: "base64", media_type: "image/jpeg", data: "..."}}]}</li>
 *   <li><b>Gemini:</b> {@code parts: [{text: "..."}, {inline_data: {mime_type: "image/jpeg", data: "..."}}]}</li>
 *   <li><b>OpenAI-compatible:</b> passthrough (no translation needed)</li>
 * </ul>
 */
public final class ContentTranslator {

    private ContentTranslator() {
    }

    // ========================================================================
    // → Message-level conversion
    // ========================================================================

    /**
     * Convert a gateway message into an Anthropic-format content blocks list.
     * Each message's content becomes one content array in Anthropic's format.
     */
    public static List<Map<String, Object>> toAnthropicContent(ChatMessage message) {
        if (message.isMultipart()) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (ContentPart part : message.contentParts()) {
                blocks.add(toAnthropicBlock(part));
            }
            return blocks;
        }
        // Simple text
        return List.of(Map.of("type", "text", "text", message.textContent()));
    }

    /**
     * Convert a single content part into an Anthropic content block.
     */
    private static Map<String, Object> toAnthropicBlock(ContentPart part) {
        return switch (part) {
            case TextPart tp -> Map.of("type", "text", "text", tp.text());
            case ImagePart ip -> toAnthropicImage(ip);
        };
    }

    private static Map<String, Object> toAnthropicImage(ImagePart ip) {
        var src = ip.imageUrl();
        if (src == null || src.url() == null) {
            return Map.of("type", "text", "text", "");
        }
        if (src.isBase64()) {
            return Map.of(
                    "type", "image",
                    "source", Map.of(
                            "type", "base64",
                            "media_type", src.mimeType(),
                            "data", src.base64Data()
                    )
            );
        }
        // URL-based image (Anthropic doesn't support direct URLs, send as text)
        return Map.of("type", "text", "text", "[Image: " + src.url() + "]");
    }

    // ========================================================================
    // → Gemini format conversion
    // ========================================================================

    /**
     * Convert a gateway message into Gemini-format parts list.
     */
    public static List<Map<String, Object>> toGeminiParts(ChatMessage message) {
        if (message.isMultipart()) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (ContentPart part : message.contentParts()) {
                parts.add(toGeminiPart(part));
            }
            return parts;
        }
        return List.of(Map.of("text", message.textContent()));
    }

    private static Map<String, Object> toGeminiPart(ContentPart part) {
        return switch (part) {
            case TextPart tp -> Map.of("text", tp.text());
            case ImagePart ip -> toGeminiInlineData(ip);
        };
    }

    private static Map<String, Object> toGeminiInlineData(ImagePart ip) {
        var src = ip.imageUrl();
        if (src == null || src.url() == null) {
            return Map.of("text", "");
        }
        if (src.isBase64()) {
            return Map.of(
                    "inline_data", Map.of(
                            "mime_type", src.mimeType(),
                            "data", src.base64Data()
                    )
            );
        }
        // URL-based: Gemini may support direct URLs, but safer to inline
        return Map.of("text", "[Image: " + src.url() + "]");
    }
}
