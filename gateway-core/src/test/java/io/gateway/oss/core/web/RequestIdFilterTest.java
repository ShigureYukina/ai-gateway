package io.gateway.oss.core.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void filter_generatesRequestIdStoresItAndClearsMdcAfterCompletion() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/healthz").build());

        WebFilterChain chain = mutatedExchange -> {
            String requestId = mutatedExchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
            assertNotNull(requestId);
            assertTrue(requestId.startsWith("req_"));
            assertEquals(requestId, MDC.get(RequestIdFilter.REQUEST_ID_ATTR));
            assertEquals(requestId, mutatedExchange.getRequest().getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        String requestId = exchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        assertNotNull(requestId);
        assertTrue(requestId.startsWith("req_"));
        assertEquals(requestId, exchange.getResponse().getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get(RequestIdFilter.REQUEST_ID_ATTR));
    }

    @Test
    void filter_reusesIncomingRequestIdHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/healthz").header(RequestIdFilter.REQUEST_ID_HEADER, "req-fixed").build()
        );

        WebFilterChain chain = mutatedExchange -> {
            assertEquals("req-fixed", mutatedExchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTR));
            assertEquals("req-fixed", MDC.get(RequestIdFilter.REQUEST_ID_ATTR));
            assertEquals("req-fixed", mutatedExchange.getRequest().getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertEquals("req-fixed", exchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTR));
        assertEquals("req-fixed", exchange.getResponse().getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get(RequestIdFilter.REQUEST_ID_ATTR));
    }
}
