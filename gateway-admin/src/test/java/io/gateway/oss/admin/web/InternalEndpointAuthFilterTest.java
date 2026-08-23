package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.security.ClientAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;

/**
 * {@link InternalEndpointAuthFilter} 的单元测试。
 * 验证 /internal/* 路径的认证拦截、非 /internal/ 路径的放行、以及错误响应。
 */
class InternalEndpointAuthFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClientAuthService clientAuthService = mock();
    private final WebFilterChain chain = mock();
    private final InternalEndpointAuthFilter filter = new InternalEndpointAuthFilter(clientAuthService, objectMapper);

    // ─── Non-/internal/ paths pass through ───

    @Test
    void shouldAllowNonInternalPathWithoutAuth() {
        var exchange = exchangeForPath("/public/test");

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(clientAuthService);
    }

    @Test
    void shouldAllowHealthzPathWithoutAuth() {
        var exchange = exchangeForPath("/healthz");

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    // ─── /internal/* rejects without auth ───

    @Test
    void shouldReturn401WhenNoAuthHeader() {
        var exchange = exchangeForPath("/internal/test");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        var response = exchange.getResponse();
        assert response.getStatusCode() == HttpStatus.UNAUTHORIZED;
        verify(chain, never()).filter(exchange);
    }

    @Test
    void shouldReturn401WhenAuthHeaderNotBearer() {
        var exchange = exchangeForPath("/internal/test", "Basic dGVzdDpwYXNz");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assert exchange.getResponse().getStatusCode() == HttpStatus.UNAUTHORIZED;
        verify(chain, never()).filter(exchange);
    }

    @Test
    void shouldReturn401WithJsonErrorBody() {
        var exchange = exchangeForPath("/internal/test");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assert exchange.getResponse().getStatusCode() == HttpStatus.UNAUTHORIZED;
        assert exchange.getResponse().getHeaders().getContentType() != null;
    }

    // ─── /internal/* with valid auth ───

    @Test
    void shouldAllowInternalPathWithValidToken() {
        var exchange = exchangeForPath("/internal/test", "Bearer valid-token");
        var principal = new ClientPrincipal("client-1", null, "admin");

        when(clientAuthService.authenticate("Bearer valid-token")).thenReturn(principal);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assert exchange.getResponse().getStatusCode() == null; // not set = pass through
        assert exchange.getAttribute(InternalEndpointAuthFilter.CLIENT_PRINCIPAL_ATTR) == principal;
        verify(chain).filter(exchange);
    }

    // ─── /internal/* with invalid token ───

    @Test
    void shouldReturn401WithInvalidToken() {
        var exchange = exchangeForPath("/internal/test", "Bearer invalid-token");

        when(clientAuthService.authenticate("Bearer invalid-token"))
                .thenThrow(new GatewayException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid token"));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assert exchange.getResponse().getStatusCode() == HttpStatus.UNAUTHORIZED;
        verify(chain, never()).filter(exchange);
    }

    // ─── /internal/* with runtime error during auth ───

    @Test
    void shouldHandleRuntimeException() {
        var exchange = exchangeForPath("/internal/test", "Bearer crash");

        when(clientAuthService.authenticate("Bearer crash"))
                .thenThrow(new RuntimeException("Unexpected error"));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assert exchange.getResponse().getStatusCode() == HttpStatus.UNAUTHORIZED;
        verify(chain, never()).filter(exchange);
    }

    // ─── helpers ───

    private static MockServerWebExchange exchangeForPath(String path) {
        return exchangeForPath(path, null);
    }

    private static MockServerWebExchange exchangeForPath(String path, String authHeader) {
        var builder = MockServerHttpRequest.get(path);
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return MockServerWebExchange.from(builder.build());
    }
}
