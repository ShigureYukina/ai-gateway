package io.gateway.oss.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An image content part in a multi-modal message content array.
 *
 * <p>Corresponds to OpenAI's {@code {"type": "image_url", "image_url": {"url": "...", "detail": "auto"}}} format.</p>
 *
 * <p>The {@code url} can be either a direct HTTPS URL or a data URI
 * ({@code data:image/jpeg;base64,...}). The gateway translates these
 * to each provider's native image format:</p>
 * <ul>
 *   <li><b>Anthropic:</b> {@code {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "..."}}}</li>
 *   <li><b>Gemini:</b> {@code {"inline_data": {"mime_type": "image/jpeg", "data": "..."}}}</li>
 * </ul>
 */
public record ImagePart(
    @JsonProperty("type") String type,
    @JsonProperty("image_url") ImageSource imageUrl
) implements ContentPart {
    public ImagePart(ImageSource imageUrl) {
        this("image_url", imageUrl);
    }

    /** OpenAI's image_url source object. */
    public record ImageSource(
        String url,
        String detail
    ) {
        public ImageSource(String url) {
            this(url, "auto");
        }

        /** Whether this image is provided as a base64 data URI. */
        public boolean isBase64() {
            return url != null && url.startsWith("data:");
        }

        /** Extract MIME type from data URI, e.g. {@code data:image/jpeg;base64,...} → {@code image/jpeg}. */
        public String mimeType() {
            if (url == null) return "image/png";
            if (url.startsWith("data:")) {
                int semi = url.indexOf(';');
                if (semi > 5) return url.substring(5, semi);
            }
            // Infer from extension
            if (url.endsWith(".jpg") || url.endsWith(".jpeg")) return "image/jpeg";
            if (url.endsWith(".png")) return "image/png";
            if (url.endsWith(".webp")) return "image/webp";
            if (url.endsWith(".gif")) return "image/gif";
            return "image/png";
        }

        /** Extract base64 data portion from a data URI. */
        public String base64Data() {
            if (url == null || !url.startsWith("data:")) return null;
            int comma = url.indexOf(',');
            return comma >= 0 ? url.substring(comma + 1) : null;
        }
    }
}
