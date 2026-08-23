package io.gateway.oss.core.web;

import org.slf4j.MDC;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class RequestIdFilter implements WebFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTR = "requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = "req_" + UUID.randomUUID();
        }
        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);
        ServerHttpRequest mutated = exchange.getRequest().mutate().header(REQUEST_ID_HEADER, requestId).build();
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        MDC.put(REQUEST_ID_ATTR, requestId);
        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signal -> MDC.remove(REQUEST_ID_ATTR));
    }
}
