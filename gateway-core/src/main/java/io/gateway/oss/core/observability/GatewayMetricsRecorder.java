package io.gateway.oss.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;

@Component
public class GatewayMetricsRecorder {

    public static final String REQUEST_START_NS_ATTR = "requestStartNs";

    private final MeterRegistry meterRegistry;

    public GatewayMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void markRequestStart(ServerWebExchange exchange) {
        exchange.getAttributes().putIfAbsent(REQUEST_START_NS_ATTR, System.nanoTime());
    }

    public void recordSuccess(ServerWebExchange exchange, int status) {
        record(exchange, status, "success");
    }

    public void recordFailure(ServerWebExchange exchange, int status) {
        record(exchange, status, "failure");
    }

    private void record(ServerWebExchange exchange, int status, String outcome) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        String statusTag = String.valueOf(status);
        long elapsedNs = elapsedNanos(exchange);

        meterRegistry.counter("gateway.request.count",
                "path", path,
                "method", method)
                .increment();

        meterRegistry.counter("gateway.request.outcome",
                "path", path,
                "method", method,
                "outcome", outcome,
                "status", statusTag)
                .increment();

        Timer.builder("gateway.request.latency")
                .description("Gateway request latency")
                .tags("path", path,
                        "method", method,
                        "outcome", outcome,
                        "status", statusTag)
                .register(meterRegistry)
                .record(Duration.ofNanos(elapsedNs));
    }

    public void recordQuotaRemaining(String clientId, long dailyUsed, long dailyLimit,
                                      long monthlyUsed, long monthlyLimit) {
        if (dailyLimit > 0) {
            AtomicLong dailyGauge = meterRegistry.gauge("gateway.quota.daily.remaining",
                    List.of(Tag.of("clientId", clientId)), new AtomicLong(0));
            if (dailyGauge != null) dailyGauge.set(Math.max(0, dailyLimit - dailyUsed));
        }
        if (monthlyLimit > 0) {
            AtomicLong monthlyGauge = meterRegistry.gauge("gateway.quota.monthly.remaining",
                    List.of(Tag.of("clientId", clientId)), new AtomicLong(0));
            if (monthlyGauge != null) monthlyGauge.set(Math.max(0, monthlyLimit - monthlyUsed));
        }
    }

    public void recordCircuitState(String routeId, boolean open) {
        AtomicLong gauge = meterRegistry.gauge("gateway.circuit_breaker.state",
                List.of(Tag.of("routeId", routeId)), new AtomicLong(0));
        if (gauge != null) gauge.set(open ? 1L : 0L);
    }

    public void recordUpstreamLatency(String provider, long millis) {
        Timer.builder("gateway.upstream.latency")
                .description("Upstream provider response latency")
                .tag("provider", provider)
                .register(meterRegistry)
                .record(Duration.ofMillis(millis));
    }

    public void recordWriteLatency(String writePoint, long durationMs) {
        Timer.builder("gateway.write.latency")
                .description("Write path latency")
                .tag("writePoint", writePoint)
                .publishPercentileHistogram()
                .publishPercentiles(0.95)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    public void recordBatchFlusherTaskWait(String taskClass, long durationMs) {
        Timer.builder("gateway.batch_flusher.task.wait")
                .description("Batch flusher task queue wait time")
                .tag("taskClass", taskClass)
                .publishPercentileHistogram()
                .publishPercentiles(0.95)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    public void recordBatchFlusherTaskExecution(String taskClass, long durationMs) {
        Timer.builder("gateway.batch_flusher.task.exec")
                .description("Batch flusher task execution time")
                .tag("taskClass", taskClass)
                .publishPercentileHistogram()
                .publishPercentiles(0.95)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    public void recordBatchFlusherOverload(String action, String taskClass) {
        Counter.builder("gateway.batch_flusher.overload")
                .description("Batch flusher overload actions")
                .tags("action", action, "taskClass", taskClass)
                .register(meterRegistry)
                .increment();
    }

    public AtomicLong registerGauge(String name, List<Tag> tags, AtomicLong value) {
        AtomicLong gauge = meterRegistry.gauge(name, tags, value);
        return gauge != null ? gauge : value;
    }

    public <T> void registerFunctionCounter(String name, List<Tag> tags, T stateObject, ToDoubleFunction<T> countFunction) {
        FunctionCounter.builder(name, stateObject, countFunction)
                .tags(tags)
                .register(meterRegistry);
    }

    public void recordResilience4jCircuitBreakerCall(String routeId, String kind) {
        meterRegistry.counter("gateway.resilience4j.circuitbreaker.calls",
                "routeId", routeId,
                "kind", kind)
                .increment();
    }

    public void recordResilience4jRetryCall(String routeId, String kind) {
        meterRegistry.counter("gateway.resilience4j.retry.calls",
                "routeId", routeId,
                "kind", kind)
                .increment();
    }

    public long elapsedMillis(ServerWebExchange exchange) {
        return elapsedNanos(exchange) / 1_000_000;
    }

    private long elapsedNanos(ServerWebExchange exchange) {
        Object start = exchange.getAttribute(REQUEST_START_NS_ATTR);
        if (start instanceof Long startNs) {
            return Math.max(0, System.nanoTime() - startNs);
        }
        return 0L;
    }
}
