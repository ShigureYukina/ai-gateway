package io.gateway.oss.core.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Schema(description = "OpenAI-compatible chat completion request with full field passthrough")
public class ChatCompletionsRequest {

    public static final String GATEWAY_REQUEST_ID_EXTRA = "_gateway_request_id";

    private String model;
    private List<ChatMessage> messages;
    private Boolean stream;
    private Double temperature;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    private List<Map<String, Object>> tools;
    @JsonProperty("tool_choice")
    private Object toolChoice;
    @JsonProperty("response_format")
    private Map<String, Object> responseFormat;
    private final Map<String, Object> extras = new HashMap<>();

    @JsonCreator
    ChatCompletionsRequest() {
    }

    public ChatCompletionsRequest(String model, List<ChatMessage> messages, Boolean stream,
                                  Double temperature, Integer maxTokens,
                                  List<Map<String, Object>> tools, Object toolChoice,
                                  Map<String, Object> responseFormat, Map<String, Object> extras) {
        this.model = model;
        this.messages = messages != null ? messages : List.of();
        this.stream = stream;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.tools = tools;
        this.toolChoice = toolChoice;
        this.responseFormat = responseFormat;
        if (extras != null) this.extras.putAll(extras);
    }

    public ChatCompletionsRequest(String model, List<ChatMessage> messages, Boolean stream,
                                  Double temperature, Integer maxTokens) {
        this(model, messages, stream, temperature, maxTokens, null, null, null, null);
    }

    @Schema(description = "External model alias", example = "gpt-4o-mini")
    @NotBlank
    public String model() { return model; }

    @Schema(description = "Conversation messages")
    @NotEmpty
    public List<@Valid ChatMessage> messages() { return messages; }

    @Schema(description = "Enable SSE streaming", example = "false")
    public Boolean stream() { return stream; }

    @Schema(example = "0.7")
    public Double temperature() { return temperature; }

    @Schema(name = "max_tokens", example = "256")
    public Integer maxTokens() { return maxTokens; }

    @Schema(description = "Tool definitions (OpenAI format)")
    public List<Map<String, Object>> tools() { return tools; }

    @Schema(description = "Tool choice: auto|none|required|{type:function,function:{name:...}}")
    public Object toolChoice() { return toolChoice; }

    @Schema(description = "Response format: {type:json_object} or {type:json_schema,json_schema:{...}}")
    public Map<String, Object> responseFormat() { return responseFormat; }

    public Map<String, Object> extras() { return extras; }

    @JsonAnySetter
    void setExtra(String key, Object value) {
        extras.put(key, value);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>(extras);
        if (model != null) map.put("model", model);
        if (messages != null) map.put("messages", messages);
        if (stream != null) map.put("stream", stream);
        if (temperature != null) map.put("temperature", temperature);
        if (maxTokens != null) map.put("max_tokens", maxTokens);
        if (tools != null) map.put("tools", tools);
        if (toolChoice != null) map.put("tool_choice", toolChoice);
        if (responseFormat != null) map.put("response_format", responseFormat);
        return map;
    }

    public boolean streamEnabled() {
        return Boolean.TRUE.equals(stream);
    }
}
