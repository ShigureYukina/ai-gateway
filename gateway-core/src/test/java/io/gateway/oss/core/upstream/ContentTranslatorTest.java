package io.gateway.oss.core.upstream;

import io.gateway.oss.core.dto.ChatMessage;
import io.gateway.oss.core.dto.ContentPart;
import io.gateway.oss.core.dto.ImagePart;
import io.gateway.oss.core.dto.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContentTranslator} 的单元测试。
 * 覆盖简单文本、多模态消息（文本+图片）、base64/URL 图片回退等场景。
 */
class ContentTranslatorTest {

    // ========================================================================
    // Anthropic format
    // ========================================================================

    @Test
    void toAnthropicContentWithSimpleText() {
        var msg = new ChatMessage("user", "Hello");
        List<Map<String, Object>> result = ContentTranslator.toAnthropicContent(msg);

        assertEquals(1, result.size());
        assertEquals("text", result.getFirst().get("type"));
        assertEquals("Hello", result.getFirst().get("text"));
    }

    @Test
    void toAnthropicContentWithMultipartTextAndImage() {
        ContentPart textPart = new TextPart("What's in this image?");
        ContentPart imagePart = new ImagePart(
                new ImagePart.ImageSource("data:image/png;base64,iVBORw0KGgoAAAANSU", "auto"));
        var msg = new ChatMessage("user", List.of(textPart, imagePart));

        List<Map<String, Object>> result = ContentTranslator.toAnthropicContent(msg);

        assertEquals(2, result.size());

        // First block: text
        assertEquals("text", result.get(0).get("type"));
        assertEquals("What's in this image?", result.get(0).get("text"));

        // Second block: image (base64)
        assertEquals("image", result.get(1).get("type"));
        var source = (Map<String, Object>) result.get(1).get("source");
        assertEquals("base64", source.get("type"));
        assertEquals("image/png", source.get("media_type"));
        assertEquals("iVBORw0KGgoAAAANSU", source.get("data"));
    }

    @Test
    void toAnthropicContentWithUrlImageFallsBackToText() {
        ContentPart imagePart = new ImagePart(
                new ImagePart.ImageSource("https://example.com/image.jpg"));
        var msg = new ChatMessage("user", List.of(imagePart));

        List<Map<String, Object>> result = ContentTranslator.toAnthropicContent(msg);

        assertEquals(1, result.size());
        assertEquals("text", result.getFirst().get("type"));
        assertEquals("[Image: https://example.com/image.jpg]", result.getFirst().get("text"));
    }

    @Test
    void toAnthropicContentWithNullImageUrlReturnsEmptyText() {
        ContentPart imagePart = new ImagePart((ImagePart.ImageSource) null);
        var msg = new ChatMessage("user", List.of(imagePart));

        List<Map<String, Object>> result = ContentTranslator.toAnthropicContent(msg);

        assertEquals(1, result.size());
        assertEquals("text", result.getFirst().get("type"));
        assertEquals("", result.getFirst().get("text"));
    }

    // ========================================================================
    // Gemini format
    // ========================================================================

    @Test
    void toGeminiPartsWithSimpleText() {
        var msg = new ChatMessage("user", "Hello");
        List<Map<String, Object>> result = ContentTranslator.toGeminiParts(msg);

        assertEquals(1, result.size());
        assertEquals("Hello", result.getFirst().get("text"));
    }

    @Test
    void toGeminiPartsWithMultipartTextAndImage() {
        ContentPart textPart = new TextPart("Describe this image");
        ContentPart imagePart = new ImagePart(
                new ImagePart.ImageSource("data:image/jpeg;base64,/9j/4AAQSkZJRg", "auto"));
        var msg = new ChatMessage("user", List.of(textPart, imagePart));

        List<Map<String, Object>> result = ContentTranslator.toGeminiParts(msg);

        assertEquals(2, result.size());

        // First part: text
        assertEquals("Describe this image", result.get(0).get("text"));

        // Second part: inline_data
        var inlineData = (Map<String, Object>) result.get(1).get("inline_data");
        assertEquals("image/jpeg", inlineData.get("mime_type"));
        assertEquals("/9j/4AAQSkZJRg", inlineData.get("data"));
    }

    @Test
    void toGeminiPartsWithUrlImageFallsBackToText() {
        ContentPart imagePart = new ImagePart(
                new ImagePart.ImageSource("https://example.com/photo.webp"));
        var msg = new ChatMessage("user", List.of(imagePart));

        List<Map<String, Object>> result = ContentTranslator.toGeminiParts(msg);

        assertEquals(1, result.size());
        assertEquals("[Image: https://example.com/photo.webp]", result.getFirst().get("text"));
    }

    @Test
    void toGeminiPartsWithNullImageUrlReturnsEmptyText() {
        ContentPart imagePart = new ImagePart((ImagePart.ImageSource) null);
        var msg = new ChatMessage("user", List.of(imagePart));

        List<Map<String, Object>> result = ContentTranslator.toGeminiParts(msg);

        assertEquals(1, result.size());
        assertEquals("", result.getFirst().get("text"));
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    void toAnthropicContentWithEmptyPartsShouldNotThrow() {
        var msg = new ChatMessage("user", (List<ContentPart>) null);
        // Non-multipart path uses textContent which is null → fallback to text block
        List<Map<String, Object>> result = ContentTranslator.toAnthropicContent(msg);
        assertNotNull(result);
    }

    @Test
    void toGeminiPartsWithEmptyPartsShouldNotThrow() {
        var msg = new ChatMessage("user", (List<ContentPart>) null);
        List<Map<String, Object>> result = ContentTranslator.toGeminiParts(msg);
        assertNotNull(result);
    }
}
