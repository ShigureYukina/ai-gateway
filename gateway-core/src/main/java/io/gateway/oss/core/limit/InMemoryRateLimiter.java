package io.gateway.oss.core.limit;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.ClientLimits;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.error.GatewayException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free per-client rate limiter using CAS-based packed AtomicLong.
 * High 32 bits = window epoch (epochSeconds / windowSeconds), low 32 bits = count.
 */
public class InMemoryRateLimiter implements ClientRateLimiter {

    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, AtomicLong> buckets = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void check(String clientId) {
        ClientLimits clientLimits = resolveClientLimits(clientId);
        long windowSeconds = resolveWindowSeconds(clientLimits);
        if (windowSeconds <= 0) windowSeconds = 60;
        int maxRequests = resolveMaxRequests(clientLimits);

        AtomicLong packed = buckets.computeIfAbsent(clientId, k -> new AtomicLong(0L));

        while (true) {
            long current = packed.get();
            long currentWindow = current >>> 32;
            long currentCount = current & 0xFFFFFFFFL;

            long nowEpoch = System.currentTimeMillis() / 1000 / windowSeconds;

            long newWindow;
            long newCount;
            if (nowEpoch != currentWindow) {
                // Window changed — reset count
                newWindow = nowEpoch;
                newCount = 1;
            } else {
                newWindow = currentWindow;
                newCount = currentCount + 1;
            }

            if (newCount > maxRequests) {
                throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Request limit exceeded");
            }

            long newPacked = (newWindow << 32) | (newCount & 0xFFFFFFFFL);
            if (packed.compareAndSet(current, newPacked)) {
                return;
            }
            // CAS failed — retry
        }
    }

    @Override
    public RateLimitStatus getCurrentStatus(String clientId) {
        ClientLimits clientLimits = resolveClientLimits(clientId);
        long windowSeconds = resolveWindowSeconds(clientLimits);
        if (windowSeconds <= 0) windowSeconds = 60;
        int maxRequests = resolveMaxRequests(clientLimits);

        AtomicLong bucket = buckets.get(clientId);
        if (bucket == null) {
            long windowStart = System.currentTimeMillis() / 1000 / windowSeconds;
            return new RateLimitStatus(maxRequests, maxRequests, (windowStart + 1) * windowSeconds);
        }

        long packed = bucket.get();
        long currentWindow = packed >>> 32;
        long currentCount = packed & 0xFFFFFFFFL;
        long nowEpoch = System.currentTimeMillis() / 1000 / windowSeconds;

        if (nowEpoch != currentWindow) {
            long windowStart = System.currentTimeMillis() / 1000 / windowSeconds;
            return new RateLimitStatus(maxRequests, maxRequests, (windowStart + 1) * windowSeconds);
        }

        int remaining = Math.max(0, maxRequests - (int) currentCount);
        long resetEpoch = (currentWindow + 1) * windowSeconds;
        return new RateLimitStatus(maxRequests, remaining, resetEpoch);
    }

    @Override
    public void reset() {
        buckets.clear();
    }

    private ClientLimits resolveClientLimits(String clientId) {
        if (clientId == null) return null;
        var clients = properties.getClients();
        if (clients == null) return null;
        ClientConfig clientConfig = clients.get(clientId);
        if (clientConfig == null) return null;
        return clientConfig.getLimits();
    }

    private long resolveWindowSeconds(ClientLimits clientLimits) {
        if (clientLimits != null && clientLimits.getWindow() != null) {
            return clientLimits.getWindow().getSeconds();
        }
        Duration globalWindow = properties.getLimit().getWindow();
        return globalWindow != null ? globalWindow.getSeconds() : 60;
    }

    private int resolveMaxRequests(ClientLimits clientLimits) {
        if (clientLimits != null && clientLimits.getRequestsPerWindow() != null) {
            return clientLimits.getRequestsPerWindow();
        }
        return properties.getLimit().getRequestsPerWindow();
    }
}
