package io.gateway.oss.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LimitConfigTest {

    @Test
    void defaultValuesMatchExpectedConfiguration() {
        LimitConfig config = new LimitConfig();

        assertEquals(60, config.getRequestsPerWindow());
        assertEquals(Duration.ofMinutes(1), config.getWindow());
    }

    @Test
    void settersAndGettersRoundTrip() {
        LimitConfig config = new LimitConfig();
        Duration window = Duration.ofSeconds(30);

        config.setRequestsPerWindow(120);
        config.setWindow(window);

        assertEquals(120, config.getRequestsPerWindow());
        assertEquals(window, config.getWindow());
    }
}
