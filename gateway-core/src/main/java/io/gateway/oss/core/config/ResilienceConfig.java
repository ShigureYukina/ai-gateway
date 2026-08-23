package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.ResilienceConfigView;
import jakarta.validation.constraints.Min;

import java.time.Duration;

public class ResilienceConfig implements ResilienceConfigView {

    @Min(1)
    private int maxAttempts = 2;
    @Min(1)
    private int retryableFailureThreshold = 2;
    private Duration failureWindow = Duration.ofSeconds(30);
    private Duration openDuration = Duration.ofSeconds(30);

    // Resilience4j CircuitBreaker config
    private float slowCallRateThreshold = 100;
    private Duration slowCallDurationThreshold = Duration.ofSeconds(5);
    private String slidingWindowType = "COUNT_BASED";
    private int slidingWindowSize = 10;
    @Min(1)
    private int minimumNumberOfCalls = 100;
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);
    private int permittedNumberOfCallsInHalfOpenState = 3;

    // Resilience4j Bulkhead config (default high enough to not restrict existing traffic)
    private int bulkheadMaxConcurrent = 1000;
    private Duration bulkheadMaxWaitDuration = Duration.ofMillis(0);

    // Resilience4j Retry config
    private int retryMaxAttempts = 1;
    private Duration retryWaitDuration = Duration.ofMillis(500);
    private float retryExponentialBackoffMultiplier = 2;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getRetryableFailureThreshold() {
        return retryableFailureThreshold;
    }

    public void setRetryableFailureThreshold(int retryableFailureThreshold) {
        this.retryableFailureThreshold = retryableFailureThreshold;
    }

    public Duration getFailureWindow() {
        return failureWindow;
    }

    public void setFailureWindow(Duration failureWindow) {
        this.failureWindow = failureWindow;
    }

    public Duration getOpenDuration() {
        return openDuration;
    }

    public void setOpenDuration(Duration openDuration) {
        this.openDuration = openDuration;
    }

    public float getSlowCallRateThreshold() {
        return slowCallRateThreshold;
    }

    public void setSlowCallRateThreshold(float slowCallRateThreshold) {
        this.slowCallRateThreshold = slowCallRateThreshold;
    }

    public Duration getSlowCallDurationThreshold() {
        return slowCallDurationThreshold;
    }

    public void setSlowCallDurationThreshold(Duration slowCallDurationThreshold) {
        this.slowCallDurationThreshold = slowCallDurationThreshold;
    }

    public String getSlidingWindowType() {
        return slidingWindowType;
    }

    public void setSlidingWindowType(String slidingWindowType) {
        this.slidingWindowType = slidingWindowType;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }

    public Duration getWaitDurationInOpenState() {
        return waitDurationInOpenState;
    }

    public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
        this.waitDurationInOpenState = waitDurationInOpenState;
    }

    public int getPermittedNumberOfCallsInHalfOpenState() {
        return permittedNumberOfCallsInHalfOpenState;
    }

    public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
    }

    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
        this.minimumNumberOfCalls = minimumNumberOfCalls;
    }

    public int getBulkheadMaxConcurrent() {
        return bulkheadMaxConcurrent;
    }

    public void setBulkheadMaxConcurrent(int bulkheadMaxConcurrent) {
        this.bulkheadMaxConcurrent = bulkheadMaxConcurrent;
    }

    public Duration getBulkheadMaxWaitDuration() {
        return bulkheadMaxWaitDuration;
    }

    public void setBulkheadMaxWaitDuration(Duration bulkheadMaxWaitDuration) {
        this.bulkheadMaxWaitDuration = bulkheadMaxWaitDuration;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public Duration getRetryWaitDuration() {
        return retryWaitDuration;
    }

    public void setRetryWaitDuration(Duration retryWaitDuration) {
        this.retryWaitDuration = retryWaitDuration;
    }

    public float getRetryExponentialBackoffMultiplier() {
        return retryExponentialBackoffMultiplier;
    }

    public void setRetryExponentialBackoffMultiplier(float retryExponentialBackoffMultiplier) {
        this.retryExponentialBackoffMultiplier = retryExponentialBackoffMultiplier;
    }
}
