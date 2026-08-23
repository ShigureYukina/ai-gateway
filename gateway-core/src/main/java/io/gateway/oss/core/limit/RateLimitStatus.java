package io.gateway.oss.core.limit;

/**
 * Rate limit state for response headers.
 *
 * @param limit             maximum requests allowed in the window
 * @param remaining         requests remaining in the current window
 * @param resetEpochSeconds Unix epoch second when the window resets
 */
public record RateLimitStatus(int limit, int remaining, long resetEpochSeconds) {
}
