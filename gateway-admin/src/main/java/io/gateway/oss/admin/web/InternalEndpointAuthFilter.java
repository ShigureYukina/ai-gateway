package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.dto.GatewayErrorResponse;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.security.ClientAuthService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalEndpointAuthFilter implements WebFilter {

    public static final String CLIENT_PRINCIPAL_ATTR = "clientPrincipal";

    private final ClientAuthService clientAuthService;
    private final ObjectMapper objectMapper;

    public InternalEndpointAuthFilter(ClientAuthService clientAuthService, ObjectMapper objectMapper) {
        this.clientAuthService = clientAuthService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/internal/")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeError(exchange, HttpStatus.UNAUTHORIZED, "unauthorized", "Authorization header required for /internal/** endpoints");
        }

        try {
            var principal = clientAuthService.authenticate(authHeader);
            exchange.getAttributes().put(CLIENT_PRINCIPAL_ATTR, principal);
            return chain.filter(exchange);
        } catch (GatewayException e) {
            return writeError(exchange, e.getStatus(), e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            return writeError(exchange, HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication failed");
        }
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var error = new GatewayErrorResponse(code, message, exchange.getRequest().getId());
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(error);
        } catch (JsonProcessingException e) {
            bytes = "{\"code\":\"internal_error\",\"message\":\"Failed to render error response\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    static ClientPrincipal requiredPrincipal(ServerWebExchange exchange) {
        return exchange.getRequiredAttribute(CLIENT_PRINCIPAL_ATTR);
    }
}
