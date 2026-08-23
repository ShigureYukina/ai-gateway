package io.gateway.oss.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "Request body for replacing a provider's model list")
public record ProviderModelsUpdateRequest(
        @Schema(description = "Allowed upstream model IDs for this provider", example = "[\"gpt-4o-mini\",\"gpt-4.1\"]")
        @NotNull
        List<String> models
) {
}
