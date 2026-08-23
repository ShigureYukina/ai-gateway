package io.gateway.oss.core.contract;

import java.time.Duration;

public interface ResilienceConfigView {
    int getMaxAttempts();
    int getRetryableFailureThreshold();
    Duration getFailureWindow();
    Duration getOpenDuration();
    float getSlowCallRateThreshold();
    Duration getSlowCallDurationThreshold();
    String getSlidingWindowType();
    int getSlidingWindowSize();
    int getMinimumNumberOfCalls();
    Duration getWaitDurationInOpenState();
    int getPermittedNumberOfCallsInHalfOpenState();
    int getBulkheadMaxConcurrent();
    Duration getBulkheadMaxWaitDuration();
    int getRetryMaxAttempts();
    Duration getRetryWaitDuration();
    float getRetryExponentialBackoffMultiplier();
}
