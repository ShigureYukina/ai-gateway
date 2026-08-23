package io.gateway.oss.core.web.support;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.limit.ConcurrentRequestLimiter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 并发限制辅助方法。
 */
public final class ConcurrencyLimitHelper {

    private ConcurrencyLimitHelper() {
    }

    public static <T> Mono<T> withConcurrencyLimitMono(ConcurrentRequestLimiter concurrentRequestLimiter,
                                                       String clientId,
                                                       Mono<T> upstream) {
        try {
            concurrentRequestLimiter.acquire(clientId);
        } catch (GatewayException e) {
            return Mono.error(e);
        }
        return upstream
                .doOnSuccess(v -> concurrentRequestLimiter.release(clientId))
                .doOnError(v -> concurrentRequestLimiter.release(clientId))
                .doOnCancel(() -> concurrentRequestLimiter.release(clientId));
    }

    public static <T> Flux<T> withConcurrencyLimitFlux(ConcurrentRequestLimiter concurrentRequestLimiter,
                                                       String clientId,
                                                       Flux<T> upstream) {
        try {
            concurrentRequestLimiter.acquire(clientId);
        } catch (GatewayException e) {
            return Flux.error(e);
        }
        return upstream
                .doOnComplete(() -> concurrentRequestLimiter.release(clientId))
                .doOnError(v -> concurrentRequestLimiter.release(clientId))
                .doOnCancel(() -> concurrentRequestLimiter.release(clientId));
    }
}
