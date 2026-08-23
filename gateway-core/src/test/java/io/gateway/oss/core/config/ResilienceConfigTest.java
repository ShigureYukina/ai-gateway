package io.gateway.oss.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResilienceConfigTest {

    @Test
    void defaultValuesMatchExpectedConfiguration() {
        ResilienceConfig config = new ResilienceConfig();

        assertEquals(2, config.getMaxAttempts());
        assertEquals(2, config.getRetryableFailureThreshold());
        assertEquals(Duration.ofSeconds(30), config.getFailureWindow());
        assertEquals(Duration.ofSeconds(30), config.getOpenDuration());
        assertEquals(100.0f, config.getSlowCallRateThreshold());
        assertEquals(Duration.ofSeconds(5), config.getSlowCallDurationThreshold());
        assertEquals("COUNT_BASED", config.getSlidingWindowType());
        assertEquals(10, config.getSlidingWindowSize());
        assertEquals(Duration.ofSeconds(30), config.getWaitDurationInOpenState());
        assertEquals(3, config.getPermittedNumberOfCallsInHalfOpenState());
        assertEquals(1000, config.getBulkheadMaxConcurrent());
        assertEquals(Duration.ofMillis(0), config.getBulkheadMaxWaitDuration());
        assertEquals(1, config.getRetryMaxAttempts());
        assertEquals(Duration.ofMillis(500), config.getRetryWaitDuration());
        assertEquals(2.0f, config.getRetryExponentialBackoffMultiplier());
    }

    @Test
    void settersAndGettersRoundTrip() {
        ResilienceConfig config = new ResilienceConfig();
        Duration failureWindow = Duration.ofMinutes(2);
        Duration openDuration = Duration.ofMinutes(3);
        Duration slowCallDurationThreshold = Duration.ofSeconds(8);
        Duration waitDurationInOpenState = Duration.ofMinutes(1);
        Duration bulkheadMaxWaitDuration = Duration.ofMillis(250);
        Duration retryWaitDuration = Duration.ofSeconds(2);

        config.setMaxAttempts(4);
        config.setRetryableFailureThreshold(5);
        config.setFailureWindow(failureWindow);
        config.setOpenDuration(openDuration);
        config.setSlowCallRateThreshold(75.0f);
        config.setSlowCallDurationThreshold(slowCallDurationThreshold);
        config.setSlidingWindowType("TIME_BASED");
        config.setSlidingWindowSize(20);
        config.setWaitDurationInOpenState(waitDurationInOpenState);
        config.setPermittedNumberOfCallsInHalfOpenState(6);
        config.setBulkheadMaxConcurrent(250);
        config.setBulkheadMaxWaitDuration(bulkheadMaxWaitDuration);
        config.setRetryMaxAttempts(3);
        config.setRetryWaitDuration(retryWaitDuration);
        config.setRetryExponentialBackoffMultiplier(3.0f);

        assertEquals(4, config.getMaxAttempts());
        assertEquals(5, config.getRetryableFailureThreshold());
        assertEquals(failureWindow, config.getFailureWindow());
        assertEquals(openDuration, config.getOpenDuration());
        assertEquals(75.0f, config.getSlowCallRateThreshold());
        assertEquals(slowCallDurationThreshold, config.getSlowCallDurationThreshold());
        assertEquals("TIME_BASED", config.getSlidingWindowType());
        assertEquals(20, config.getSlidingWindowSize());
        assertEquals(waitDurationInOpenState, config.getWaitDurationInOpenState());
        assertEquals(6, config.getPermittedNumberOfCallsInHalfOpenState());
        assertEquals(250, config.getBulkheadMaxConcurrent());
        assertEquals(bulkheadMaxWaitDuration, config.getBulkheadMaxWaitDuration());
        assertEquals(3, config.getRetryMaxAttempts());
        assertEquals(retryWaitDuration, config.getRetryWaitDuration());
        assertEquals(3.0f, config.getRetryExponentialBackoffMultiplier());
    }

    @Test
    void durationFieldsCanBeAssignedCustomDurations() {
        ResilienceConfig config = new ResilienceConfig();

        config.setFailureWindow(Duration.ofSeconds(45));
        config.setOpenDuration(Duration.ofSeconds(90));
        config.setSlowCallDurationThreshold(Duration.ofSeconds(7));
        config.setWaitDurationInOpenState(Duration.ofSeconds(15));
        config.setBulkheadMaxWaitDuration(Duration.ofMillis(100));
        config.setRetryWaitDuration(Duration.ofMillis(750));

        assertEquals(Duration.ofSeconds(45), config.getFailureWindow());
        assertEquals(Duration.ofSeconds(90), config.getOpenDuration());
        assertEquals(Duration.ofSeconds(7), config.getSlowCallDurationThreshold());
        assertEquals(Duration.ofSeconds(15), config.getWaitDurationInOpenState());
        assertEquals(Duration.ofMillis(100), config.getBulkheadMaxWaitDuration());
        assertEquals(Duration.ofMillis(750), config.getRetryWaitDuration());
    }
}
