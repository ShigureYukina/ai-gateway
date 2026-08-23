package io.gateway.oss.core.web;

import io.gateway.oss.core.dto.GatewayErrorResponse;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ServerWebExchange;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock private GatewayMetricsRecorder metricsRecorder;
    @Mock private ServerWebExchange exchange;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(metricsRecorder);
        lenient().when(exchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)).thenReturn("req-abc123");
    }

    @Test
    void gatewayException_429_returnsProperErrorResponse() {
        GatewayException ex = new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Rate limit exceeded");

        ResponseEntity<GatewayErrorResponse> response = handler.handleGatewayException(ex, exchange);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        GatewayErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("rate_limited", body.code());
        assertEquals("Rate limit exceeded", body.message());
        assertEquals("req-abc123", body.requestId());
        verify(metricsRecorder).recordFailure(exchange, 429);
    }

    @Test
    void gatewayException_401_returnsProperErrorResponse() {
        GatewayException ex = new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid credentials");

        ResponseEntity<GatewayErrorResponse> response = handler.handleGatewayException(ex, exchange);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        GatewayErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("unauthorized", body.code());
        assertEquals("Invalid credentials", body.message());
        assertEquals("req-abc123", body.requestId());
        verify(metricsRecorder).recordFailure(exchange, 401);
    }

    @Test
    void unknownException_returns500WithGenericMessage() {
        Exception ex = new RuntimeException("Something unexpected happened");

        ResponseEntity<GatewayErrorResponse> response = handler.handleGeneric(ex, exchange);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        GatewayErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("internal_error", body.code());
        assertEquals("Internal server error", body.message());
        assertEquals("req-abc123", body.requestId());
        verify(metricsRecorder).recordFailure(exchange, 500);
    }

    @Test
    void responseFormat_matchesGatewayErrorResponseStructure() {
        GatewayException ex = new GatewayException(HttpStatus.FORBIDDEN, "forbidden_model", "Model not allowed");

        ResponseEntity<GatewayErrorResponse> response = handler.handleGatewayException(ex, exchange);

        GatewayErrorResponse body = response.getBody();
        assertNotNull(body);
        // Verify all three fields of the GatewayErrorResponse record are populated
        assertNotNull(body.code(), "code must not be null");
        assertNotNull(body.message(), "message must not be null");
        assertNotNull(body.requestId(), "requestId must not be null");
        assertEquals("forbidden_model", body.code());
        assertEquals("Model not allowed", body.message());
        assertEquals("req-abc123", body.requestId());
    }

    @Test
    void gatewayException_missingRequestId_usesUnknown() {
        when(exchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTR)).thenReturn(null);
        GatewayException ex = new GatewayException(HttpStatus.BAD_REQUEST, "bad_request", "Bad input");

        ResponseEntity<GatewayErrorResponse> response = handler.handleGatewayException(ex, exchange);

        GatewayErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("unknown", body.requestId());
    }
}
