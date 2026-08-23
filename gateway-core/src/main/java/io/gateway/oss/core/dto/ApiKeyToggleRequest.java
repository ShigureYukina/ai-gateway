package io.gateway.oss.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request body for enabling or disabling a user API key")
public record ApiKeyToggleRequest(
        @Schema(description = "Target enabled status", example = "true")
        Boolean enabled
) {
}
