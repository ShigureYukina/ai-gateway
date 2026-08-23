package io.gateway.oss.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Request body for creating or updating a provider")
public record ProviderUpsertRequest(
        @Schema(description = "Provider adapter type", example = "openai")
        String type,
        @Schema(description = "Upstream base URL", example = "https://api.openai.com")
        String baseUrl,
        @Schema(description = "Default API key for upstream provider", example = "sk-***")
        String apiKey,
        @Schema(description = "Request timeout in seconds", example = "60")
        Long timeoutSeconds,
        @Schema(description = "Whether the provider is enabled", example = "true")
        Boolean enabled,
        @Schema(description = "Pinned model list for this provider")
        List<String> models
) {
}
