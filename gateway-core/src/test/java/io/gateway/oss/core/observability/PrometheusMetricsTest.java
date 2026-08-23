package io.gateway.oss.core.observability;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrometheusMetricsTest {

    private ServerWebExchange exchange;
    private PrometheusMeterRegistry promRegistry;
    private GatewayMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        promRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        recorder = new GatewayMetricsRecorder(promRegistry);

        Map<String, Object> attributes = new HashMap<>();
        ServerHttpRequest request = mock();
        RequestPath path = mock();
        exchange = mock();
        when(exchange.getRequest()).thenReturn(request);
        when(request.getPath()).thenReturn(path);
        when(path.value()).thenReturn("/v1/chat/completions");
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        when(exchange.getAttributes()).thenReturn(attributes);
        when(exchange.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.<String>getArgument(0)));
    }

    @Test
    void shouldExposeAllMetricsViaPrometheusScrape() {
        recorder.markRequestStart(exchange);
        recorder.recordSuccess(exchange, 200);
        recorder.recordFailure(exchange, 429);
        recorder.recordQuotaRemaining("client-1", 100L, 1000L, 500L, 30000L);
        recorder.recordCircuitState("route-1", true);
        recorder.recordUpstreamLatency("openai", 150L);
        recorder.recordWriteLatency("traceStoreSave", 12L);
        recorder.recordResilience4jCircuitBreakerCall("route-1", "success");
        recorder.recordResilience4jRetryCall("route-1", "permitted");

        String scrape = promRegistry.scrape();

        assertThat(scrape).contains("gateway_request_count_total");
        assertThat(scrape).contains("gateway_request_outcome_total");
        assertThat(scrape).contains("gateway_request_latency_seconds");
        assertThat(scrape).contains("gateway_quota_daily_remaining");
        assertThat(scrape).contains("gateway_quota_monthly_remaining");
        assertThat(scrape).contains("gateway_circuit_breaker_state");
        assertThat(scrape).contains("gateway_upstream_latency_seconds");
        assertThat(scrape).contains("gateway_write_latency_seconds");
        assertThat(scrape).contains("gateway_resilience4j_circuitbreaker_calls_total");
        assertThat(scrape).contains("gateway_resilience4j_retry_calls_total");
    }

    @Test
    void shouldContainMetricTagsInPrometheusScrape() {
        recorder.markRequestStart(exchange);
        recorder.recordSuccess(exchange, 200);

        String scrape = promRegistry.scrape();

        assertThat(scrape).contains("path=\"/v1/chat/completions\"");
        assertThat(scrape).contains("method=\"POST\"");
        assertThat(scrape).contains("outcome=\"success\"");
        assertThat(scrape).contains("status=\"200\"");
    }

    @Test
    void shouldReturnEmptyScrapeWhenNoMetrics() {
        PrometheusMeterRegistry emptyRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        assertThat(emptyRegistry.scrape()).isNullOrEmpty();
    }
}
