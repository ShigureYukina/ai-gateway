package io.gateway.oss.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Request body for replacing a user's allowed model list")
public record UserAllowedModelsUpdateRequest(
        @Schema(description = "Models this user is allowed to call", example = "[\"gpt-4o-mini\",\"claude-3-5-sonnet\"]")
        List<String> allowedModels
) {
}
