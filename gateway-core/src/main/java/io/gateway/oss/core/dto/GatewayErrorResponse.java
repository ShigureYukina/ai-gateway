package io.gateway.oss.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Normalized gateway error response")
public record GatewayErrorResponse(
        @Schema(example = "invalid_request") String code,
        @Schema(example = "model is required") String message,
        @Schema(example = "req_01HXYZ") String requestId
) {
}
