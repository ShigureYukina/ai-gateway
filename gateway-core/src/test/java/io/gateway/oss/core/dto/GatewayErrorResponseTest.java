package io.gateway.oss.core.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayErrorResponseTest {

    @Test
    void constructorShouldSetAllFields() {
        GatewayErrorResponse response = new GatewayErrorResponse(
                "invalid_request",
                "model is required",
                "req_01HXYZ"
        );

        assertEquals("invalid_request", response.code());
        assertEquals("model is required", response.message());
        assertEquals("req_01HXYZ", response.requestId());
    }
}
