package io.gateway.oss.core.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Schema(description = "Chat message in OpenAI-compatible format (supports text-only, multi-modal, and tool messages)")
public class ChatMessage {

    private String role;
    private String content;
    private List<ContentPart> contentParts;
    @JsonProperty("tool_call_id")
    private String toolCallId;
    private String name;
    private final Map<String, Object> extras = new HashMap<>();

    @JsonCreator
    ChatMessage() {
    }

    public ChatMessage(String role, String content, List<ContentPart> contentParts,
                       String toolCallId, String name, Map<String, Object> extras) {
        this.role = role;
        this.content = content;
        this.contentParts = contentParts;
        this.toolCallId = toolCallId;
        this.name = name;
        if (extras != null) this.extras.putAll(extras);
    }

    public ChatMessage(String role, String content) {
        this(role, content, null, null, null, null);
    }

    public ChatMessage(String role, String content, List<ContentPart> contentParts) {
        this(role, content, contentParts, null, null, null);
    }

    public ChatMessage(String role, List<ContentPart> contentParts) {
        this(role, null, contentParts, null, null, null);
    }

    @Schema(example = "user", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    public String role() { return role; }

    @Schema(description = "Text content (text-only mode)", example = "Hello")
    public String content() { return content; }

    @Schema(description = "Multi-modal content parts", hidden = true)
    public List<@Valid ContentPart> contentParts() {
        return contentParts != null ? Collections.unmodifiableList(contentParts) : List.of();
    }

    @Schema(description = "Tool call ID for tool result messages")
    public String toolCallId() { return toolCallId; }

    @Schema(description = "Function name for tool result messages")
    public String name() { return name; }

    public Map<String, Object> extras() { return extras; }

    @JsonAnySetter
    void setExtra(String key, Object value) {
        extras.put(key, value);
    }

    public String textContent() {
        if (content != null) return content;
        if (contentParts != null) {
            StringBuilder sb = new StringBuilder();
            for (ContentPart part : contentParts) {
                if (part instanceof TextPart tp) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(tp.text());
                }
            }
            return sb.toString();
        }
        return "";
    }

    public boolean isMultipart() {
        return contentParts != null && !contentParts.isEmpty();
    }
}
