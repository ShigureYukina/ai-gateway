package io.gateway.oss.core.limit;

/**
 * Per-client rate limiter interface.
 * <p>
 * Implementations MUST be thread-safe and provide at least window-based
 * rate limiting via {@link #check(String)}. Optionally they may also
 * expose current status via {@link #getCurrentStatus(String)} for
 * rate-limit response headers.
 */
public interface ClientRateLimiter {

    /**
     * Check and consume one permit for the given client.
     *
     * @throws io.gateway.oss.core.error.GatewayException with {@code RATE_LIMITED} if exceeded
     */
    void check(String clientId);

    /**
     * Query the current rate-limit status for a client (read-only, no side effects).
     *
     * @return status snapshot, or {@code null} if the implementation does not support it
     */
    default RateLimitStatus getCurrentStatus(String clientId) {
        return null; // optional
    }

    default void reset() {
        // test helper hook for stateful implementations
    }
}
