package io.gateway.oss.core.web;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.dto.GatewayErrorResponse;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final GatewayMetricsRecorder metricsRecorder;

    public GlobalExceptionHandler(GatewayMetricsRecorder metricsRecorder) {
        this.metricsRecorder = metricsRecorder;
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<GatewayErrorResponse> handleGatewayException(GatewayException ex, ServerWebExchange exchange) {
        metricsRecorder.recordFailure(exchange, ex.getStatus().value());
        String requestId = requestId(exchange);
        return ResponseEntity.status(ex.getStatus())
                .body(new GatewayErrorResponse(ex.getCode(), ex.getMessage(), requestId));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, WebExchangeBindException.class})
    public ResponseEntity<GatewayErrorResponse> handleValidation(Exception ex, ServerWebExchange exchange) {
        metricsRecorder.recordFailure(exchange, HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new GatewayErrorResponse("invalid_request", "Invalid request payload", requestId(exchange)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GatewayErrorResponse> handleIllegalArgument(IllegalArgumentException ex, ServerWebExchange exchange) {
        logger.warn("Illegal argument for requestId={}: {}", requestId(exchange), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new GatewayErrorResponse("invalid_request", ex.getMessage(), requestId(exchange)));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<GatewayErrorResponse> handleMissingHeader(ServerWebInputException ex, ServerWebExchange exchange) {
        if (ex.getMessage() != null && ex.getMessage().contains("Authorization")) {
            metricsRecorder.recordFailure(exchange, HttpStatus.UNAUTHORIZED.value());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new GatewayErrorResponse("unauthorized", "Authorization header is required", requestId(exchange)));
        }
        metricsRecorder.recordFailure(exchange, HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new GatewayErrorResponse("invalid_request", "Invalid request payload", requestId(exchange)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GatewayErrorResponse> handleGeneric(Exception ex, ServerWebExchange exchange) {
        logger.error("Unhandled exception for requestId={}: {}", requestId(exchange), ex.getMessage());
        if (logger.isDebugEnabled()) {
            logger.debug("Stack trace for requestId={}", requestId(exchange), ex);
        }
        metricsRecorder.recordFailure(exchange, HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new GatewayErrorResponse("internal_error", "Internal server error", requestId(exchange)));
    }

    private String requestId(ServerWebExchange exchange) {
        Object id = exchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        return id == null ? "unknown" : id.toString();
    }
}
