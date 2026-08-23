package io.gateway.oss.bootstrap;

import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;

import reactor.core.publisher.Mono;

/**
 * SPA 回退 HandlerMapping：仅在前面的 Controller / 静态资源映射都未命中时，
 * 为浏览器 HTML GET 请求返回 index.html。
 * <p>
 * 与 pre-routing WebFilter 不同，本实现不会在 handler 链前抢先拦截请求，
 * 而是作为最低优先级的兜底 HandlerMapping 参与匹配，因此：
 * <ul>
 *   <li>现有 Controller 命中后，保持原始语义，不会被 SPA 回退覆盖</li>
 *   <li>现有静态资源命中后，由 ResourceWebHandler 正常返回</li>
 *   <li>仅当所有前置 HandlerMapping 都未匹配时，才返回 index.html</li>
 * </ul>
 * 出于接口语义保护考虑，API/运维前缀仍排除在 SPA 回退之外，避免缺失 API 路径被回退成 HTML。
 */
@Component
public class SpaRoutingConfig implements HandlerMapping, Ordered {

    private static final Resource INDEX_HTML = new ClassPathResource("static/index.html");

    private static final String[] EXCLUDED_PREFIXES = {
        "/auth",
        "/admin",
        "/internal",
        "/v1",
        "/healthz",
        "/actuator",
        "/swagger-ui",
        "/v3/api-docs"
    };

    private final WebHandler spaFallbackHandler = exchange -> serveIndex(exchange.getResponse());

    @Override
    public Mono<Object> getHandler(ServerWebExchange exchange) {
        if (!shouldServeIndex(exchange)) {
            return Mono.empty();
        }
        return Mono.just(spaFallbackHandler);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private boolean shouldServeIndex(ServerWebExchange exchange) {
        if (!INDEX_HTML.exists()) {
            return false;
        }

        if (exchange.getRequest().getMethod() != HttpMethod.GET) {
            return false;
        }

        if (exchange.getRequest().getHeaders().getAccept().stream()
                .noneMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_HTML))) {
            return false;
        }

        String path = exchange.getRequest().getPath().value();
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return false;
            }
        }

        return true;
    }

    private Mono<Void> serveIndex(ServerHttpResponse response) {
        response.getHeaders().setContentType(MediaType.TEXT_HTML);
        return response.writeWith(DataBufferUtils.read(INDEX_HTML, response.bufferFactory(), 4096));
    }
}
