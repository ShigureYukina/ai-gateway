package io.gateway.oss.core.limit;

import io.gateway.oss.core.config.ConcurrentLimitConfig;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-client concurrent (in-flight) request limiter.
 * <p>
 * Tracks active requests per client and globally. When the per-client or
 * global limit is exceeded, {@link #acquire(String)} throws
 * {@code CONCURRENT_LIMIT_EXCEEDED}. Callers MUST call {@link #release(String)}
 * in a {@code finally}-equivalent scope (doOnComplete / doOnError / doOnCancel).
 */
@Component
public class ConcurrentRequestLimiter {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRequestLimiter.class);

    private final ConcurrentHashMap<String, AtomicInteger> perClientCounters = new ConcurrentHashMap<>();
    private final AtomicInteger globalCounter = new AtomicInteger(0);

    private final GatewayProperties properties;

    public ConcurrentRequestLimiter(GatewayProperties properties) {
        this.properties = properties;
    }

    private ConcurrentLimitConfig config() {
        return properties.getConcurrentLimit();
    }

    /**
     * Acquire a permit for the given client. Throws if limits are exceeded.
     *
     * @return the current global in-flight count (after acquisition), or -1 if disabled
     */
    public int acquire(String clientId) {
        if (!config().isEnabled()) {
            return -1;
        }
        // Check global first (fast fail)
        int global = globalCounter.incrementAndGet();
        if (global > config().getMaxGlobal()) {
            globalCounter.decrementAndGet();
            log.warn("global_concurrent_limit_exceeded clientId={} current={} max={}",
                    redact(clientId), global, config().getMaxGlobal());
            throw ErrorCode.CONCURRENT_LIMIT_EXCEEDED.exception("Global concurrent request limit exceeded");
        }

        // Per-client check
        AtomicInteger counter = perClientCounters.computeIfAbsent(clientId, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current > config().getMaxPerClient()) {
            counter.decrementAndGet();
            globalCounter.decrementAndGet();
            log.warn("client_concurrent_limit_exceeded clientId={} current={} max={}",
                    redact(clientId), current, config().getMaxPerClient());
            throw ErrorCode.CONCURRENT_LIMIT_EXCEEDED.exception("Concurrent request limit exceeded for client");
        }

        log.debug("concurrent_acquired clientId={} clientConcurrent={} globalConcurrent={}",
                redact(clientId), current, global);
        return global;
    }

    /**
     * Release a permit for the given client. MUST be called for every acquired permit.
     */
    public void release(String clientId) {
        if (!config().isEnabled()) {
            return;
        }
        globalCounter.decrementAndGet();
        AtomicInteger counter = perClientCounters.get(clientId);
        if (counter != null) {
            int current = counter.decrementAndGet();
            if (current <= 0) {
                perClientCounters.remove(clientId, new AtomicInteger(0));
            }
        }
    }

    /**
     * Get the current global in-flight count.
     */
    public int getGlobalInFlight() {
        return globalCounter.get();
    }

    /**
     * Get the per-client in-flight count. Returns 0 if client has no active requests.
     */
    public int getClientInFlight(String clientId) {
        AtomicInteger counter = perClientCounters.get(clientId);
        return counter != null ? counter.get() : 0;
    }

    private String redact(String value) {
        if (value == null || value.length() < 6) return "***";
        return value.substring(0, 3) + "***" + value.substring(value.length() - 2);
    }
}
