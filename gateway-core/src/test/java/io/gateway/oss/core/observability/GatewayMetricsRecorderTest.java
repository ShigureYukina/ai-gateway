package io.gateway.oss.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

class GatewayMetricsRecorderTest {

    private ServerWebExchange exchange;
    private MeterRegistry meterRegistry;
    private GatewayMetricsRecorder recorder;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        recorder = new GatewayMetricsRecorder(meterRegistry);
        attributes = new HashMap<>();

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
    void shouldRecordRequestCountAndOutcomeOnSuccess() {
        recorder.markRequestStart(exchange);
        recorder.recordSuccess(exchange, 200);

        assertThat(meterRegistry.get("gateway.request.count")
                .tags("path", "/v1/chat/completions", "method", "POST")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gateway.request.outcome")
                .tags("path", "/v1/chat/completions", "method", "POST", "outcome", "success", "status", "200")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordOutcomeFailureOnRecordFailure() {
        recorder.markRequestStart(exchange);
        recorder.recordFailure(exchange, 429);

        assertThat(meterRegistry.get("gateway.request.outcome")
                .tags("outcome", "failure", "status", "429")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordLatencyTimer() {
        recorder.markRequestStart(exchange);
        recorder.recordSuccess(exchange, 200);

        assertThat(meterRegistry.get("gateway.request.latency")
                .tags("path", "/v1/chat/completions", "method", "POST", "outcome", "success", "status", "200")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void shouldReturnZeroElapsedMillisWhenNoStartTime() {
        ServerWebExchange emptyExchange = mock();
        assertThat(recorder.elapsedMillis(emptyExchange)).isZero();
    }

    @Test
    void shouldRecordQuotaRemainingGauges() {
        recorder.recordQuotaRemaining("client-1", 100L, 1000L, 500L, 30000L);

        assertThat(meterRegistry.find("gateway.quota.daily.remaining")
                .tags("clientId", "client-1")
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("gateway.quota.monthly.remaining")
                .tags("clientId", "client-1")
                .gauge()).isNotNull();
    }

    @Test
    void shouldSkipQuotaGaugeWhenLimitIsZero() {
        recorder.recordQuotaRemaining("client-1", 0L, 0L, 0L, 0L);

        assertThat(meterRegistry.find("gateway.quota.daily.remaining")
                .tags("clientId", "client-1")
                .gauge()).isNull();
        assertThat(meterRegistry.find("gateway.quota.monthly.remaining")
                .tags("clientId", "client-1")
                .gauge()).isNull();
    }

    @Test
    void shouldNotSetNegativeQuotaRemaining() {
        recorder.recordQuotaRemaining("client-1", 200L, 100L, 500L, 300L);

        assertThat(meterRegistry.find("gateway.quota.daily.remaining")
                .tags("clientId", "client-1")
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("gateway.quota.monthly.remaining")
                .tags("clientId", "client-1")
                .gauge()).isNotNull();
    }

    @Test
    void shouldRecordCircuitStateOpen() {
        recorder.recordCircuitState("route-1", true);

        assertThat(meterRegistry.find("gateway.circuit_breaker.state")
                .tags("routeId", "route-1")
                .gauge()).isNotNull();
    }

    @Test
    void shouldRecordCircuitStateClosed() {
        recorder.recordCircuitState("route-1", false);

        assertThat(meterRegistry.find("gateway.circuit_breaker.state")
                .tags("routeId", "route-1")
                .gauge()).isNotNull();
    }

    @Test
    void shouldRecordUpstreamLatency() {
        recorder.recordUpstreamLatency("openai", 150L);

        assertThat(meterRegistry.get("gateway.upstream.latency")
                .tags("provider", "openai")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void shouldRecordWriteLatency() {
        recorder.recordWriteLatency("traceStoreSave", 12L);

        assertThat(meterRegistry.get("gateway.write.latency")
                .tags("writePoint", "traceStoreSave")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void shouldRecordBatchFlusherWaitAndExecMetrics() {
        recorder.recordBatchFlusherTaskWait("CRITICAL", 8L);
        recorder.recordBatchFlusherTaskExecution("BEST_EFFORT", 5L);

        assertThat(meterRegistry.get("gateway.batch_flusher.task.wait")
                .tags("taskClass", "CRITICAL")
                .timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("gateway.batch_flusher.task.exec")
                .tags("taskClass", "BEST_EFFORT")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void shouldRecordBatchFlusherOverloadCounter() {
        recorder.recordBatchFlusherOverload("drop", "BEST_EFFORT");

        assertThat(meterRegistry.get("gateway.batch_flusher.overload")
                .tags("action", "drop", "taskClass", "BEST_EFFORT")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordResilience4jMetrics() {
        recorder.recordResilience4jCircuitBreakerCall("route-1", "success");
        recorder.recordResilience4jRetryCall("route-1", "permitted");

        assertThat(meterRegistry.get("gateway.resilience4j.circuitbreaker.calls")
                .tags("routeId", "route-1", "kind", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gateway.resilience4j.retry.calls")
                .tags("routeId", "route-1", "kind", "permitted")
                .counter().count()).isEqualTo(1.0);
    }
}
