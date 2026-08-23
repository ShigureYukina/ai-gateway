package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.error.ErrorCode;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.observability.GatewayMetricsRecorder;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class Resilience4jCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(Resilience4jCircuitBreakerService.class);

    private final GatewayProperties properties;
    private final GatewayMetricsRecorder metricsRecorder;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();

    public Resilience4jCircuitBreakerService(GatewayProperties properties,
                                             GatewayMetricsRecorder metricsRecorder) {
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
    }

    public <T> Mono<T> decorateMono(String routeId, Mono<T> upstream) {
        Retry retry = getOrCreateRetry(routeId);
        CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(routeId);
        Bulkhead bulkhead = getOrCreateBulkhead(routeId);

        return upstream
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorMap(CallNotPermittedException.class, ex ->
                        ErrorCode.CIRCUIT_BREAKER_OPEN.exception("Circuit breaker open for route: " + routeId))
                .onErrorMap(ex -> ex instanceof io.github.resilience4j.bulkhead.BulkheadFullException,
                        ex -> new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "concurrent_limit_exceeded",
                                "Bulkhead full for route: " + routeId));
    }

    public <T> Flux<T> decorateFlux(String routeId, Flux<T> upstream) {
        Retry retry = getOrCreateRetry(routeId);
        CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(routeId);
        Bulkhead bulkhead = getOrCreateBulkhead(routeId);

        return upstream
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorMap(CallNotPermittedException.class, ex ->
                        ErrorCode.CIRCUIT_BREAKER_OPEN.exception("Circuit breaker open for route: " + routeId))
                .onErrorMap(ex -> ex instanceof io.github.resilience4j.bulkhead.BulkheadFullException,
                        ex -> new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "concurrent_limit_exceeded",
                                "Bulkhead full for route: " + routeId));
    }

    /**
     * Lightweight stream decorator: single CB state check + Bulkhead acquire at subscribe time.
     * No per-signal operator layers (BulkheadOperator, CircuitBreakerOperator, RetryOperator).
     * Retry is skipped because re-subscribing a Flux mid-stream is semantically wrong.
     */
    public <T> Flux<T> decorateStreamFlux(String routeId, Flux<T> upstream) {
        return Flux.defer(() -> {
            CircuitBreaker cb = getOrCreateCircuitBreaker(routeId);
            if (cb.getState() == CircuitBreaker.State.OPEN || cb.getState() == CircuitBreaker.State.FORCED_OPEN) {
                return Flux.error(ErrorCode.CIRCUIT_BREAKER_OPEN.exception("Circuit breaker open for route: " + routeId));
            }
            Bulkhead bulkhead = getOrCreateBulkhead(routeId);
            if (!bulkhead.tryAcquirePermission()) {
                return Flux.error(new GatewayException(HttpStatus.TOO_MANY_REQUESTS,
                        "concurrent_limit_exceeded", "Bulkhead full for route: " + routeId));
            }
            return upstream
                    .doOnComplete(() -> {
                        cb.onSuccess(0, java.util.concurrent.TimeUnit.NANOSECONDS);
                        bulkhead.releasePermission();
                    })
                    .doOnError(ex -> {
                        if (!(ex instanceof CallNotPermittedException)) {
                            cb.onError(0, java.util.concurrent.TimeUnit.NANOSECONDS, ex);
                        }
                        bulkhead.releasePermission();
                    })
                    .doOnCancel(bulkhead::releasePermission)
                    .onErrorMap(ex -> ex instanceof CallNotPermittedException,
                            ex -> ErrorCode.CIRCUIT_BREAKER_OPEN.exception("Circuit breaker open for route: " + routeId))
                    .onErrorMap(ex -> ex instanceof io.github.resilience4j.bulkhead.BulkheadFullException,
                            ex -> new GatewayException(HttpStatus.TOO_MANY_REQUESTS,
                                    "concurrent_limit_exceeded", "Bulkhead full for route: " + routeId));
        });
    }

    private CircuitBreaker getOrCreateCircuitBreaker(String routeId) {
        return circuitBreakers.computeIfAbsent(routeId, id -> {
            ResilienceConfig config = properties.getResilience();
            CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .slowCallRateThreshold(config.getSlowCallRateThreshold())
                    .slowCallDurationThreshold(config.getSlowCallDurationThreshold())
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.valueOf(config.getSlidingWindowType()))
                    .slidingWindowSize(config.getSlidingWindowSize())
                    .minimumNumberOfCalls(config.getMinimumNumberOfCalls())
                    .waitDurationInOpenState(config.getWaitDurationInOpenState())
                    .permittedNumberOfCallsInHalfOpenState(config.getPermittedNumberOfCallsInHalfOpenState())
                    .build();

            CircuitBreaker cb = CircuitBreaker.of(id, cbConfig);
            registerEventListeners(id, cb);
            return cb;
        });
    }

    private Retry getOrCreateRetry(String routeId) {
        return retries.computeIfAbsent(routeId, id -> {
            ResilienceConfig config = properties.getResilience();
            IntervalFunction intervalFn = IntervalFunction.ofExponentialBackoff(
                    config.getRetryWaitDuration().toMillis(),
                    config.getRetryExponentialBackoffMultiplier());

            RetryConfig retryConfig = RetryConfig.custom()
                    .maxAttempts(config.getRetryMaxAttempts())
                    .intervalFunction(intervalFn)
                    .retryOnException(this::isRetryableException)
                    .build();

            Retry retry = Retry.of(id, retryConfig);
            registerRetryEventListeners(id, retry);
            return retry;
        });
    }

    private boolean isRetryableException(Throwable throwable) {
        if (throwable instanceof GatewayException gatewayException) {
            return shouldRetry(gatewayException);
        }
        return false;
    }

    private boolean shouldRetry(GatewayException exception) {
        if (exception.getStatus() == HttpStatus.GATEWAY_TIMEOUT) {
            return "upstream_timeout".equals(exception.getCode());
        }
        return exception.getStatus().is5xxServerError() && "upstream_error".equals(exception.getCode());
    }

    private Bulkhead getOrCreateBulkhead(String routeId) {
        return bulkheads.computeIfAbsent(routeId, id -> {
            ResilienceConfig config = properties.getResilience();
            BulkheadConfig bhConfig = BulkheadConfig.custom()
                    .maxConcurrentCalls(config.getBulkheadMaxConcurrent())
                    .maxWaitDuration(config.getBulkheadMaxWaitDuration())
                    .build();

            return Bulkhead.of(id, bhConfig);
        });
    }

    private void registerEventListeners(String routeId, CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> {
                    boolean open = event.getStateTransition().getToState() == CircuitBreaker.State.OPEN;
                    metricsRecorder.recordCircuitState(routeId, open);
                    log.info("circuit_state_transition routeId={} from={} to={}",
                            routeId,
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState());
                });

        circuitBreaker.getEventPublisher()
                .onSuccess(event ->
                        metricsRecorder.recordResilience4jCircuitBreakerCall(routeId, "success"));

        circuitBreaker.getEventPublisher()
                .onError(event ->
                        metricsRecorder.recordResilience4jCircuitBreakerCall(routeId, "failure"));

        circuitBreaker.getEventPublisher()
                .onCallNotPermitted(event ->
                        metricsRecorder.recordResilience4jCircuitBreakerCall(routeId, "not_permitted"));

        circuitBreaker.getEventPublisher()
                .onSlowCallRateExceeded(event ->
                        metricsRecorder.recordResilience4jCircuitBreakerCall(routeId, "slow_call_exceeded"));
    }

    private void registerRetryEventListeners(String routeId, Retry retry) {
        retry.getEventPublisher()
                .onRetry(event ->
                        metricsRecorder.recordResilience4jRetryCall(routeId, "retry"));

        retry.getEventPublisher()
                .onSuccess(event ->
                        metricsRecorder.recordResilience4jRetryCall(routeId, "success"));

        retry.getEventPublisher()
                .onError(event ->
                        metricsRecorder.recordResilience4jRetryCall(routeId, "failure"));
    }

    /**
     * 清空 CircuitBreaker / Retry / Bulkhead 缓存，下次请求时使用最新 {@link GatewayProperties#getResilience()} 重建。
     * 在调用了 {@link DynamicConfigService#saveSystemResilience(ResilienceConfig)} 后自动触发。
     */
    public void resetResilience() {
        int cbCount = circuitBreakers.size();
        int retryCount = retries.size();
        int bhCount = bulkheads.size();
        circuitBreakers.clear();
        retries.clear();
        bulkheads.clear();
        log.info("resilience_config_refreshed circuit_breakers={} retries={} bulkheads={} cleared", cbCount, retryCount, bhCount);
    }

    public List<String> getOpenCircuitRouteIds() {
        return circuitBreakers.entrySet().stream()
                .filter(entry -> {
                    CircuitBreaker.State state = entry.getValue().getState();
                    return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
                })
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
