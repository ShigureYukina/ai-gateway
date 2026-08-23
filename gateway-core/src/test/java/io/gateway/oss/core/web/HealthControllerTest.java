package io.gateway.oss.core.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private HealthEndpoint healthEndpoint;

    private HealthController controller;

    @BeforeEach
    void setUp() {
        controller = new HealthController(healthEndpoint);
    }

    @Test
    void health_returnsStatusAndDetailsFromEndpoint() {
        when(healthEndpoint.health()).thenReturn(Health.up().withDetail("db", "UP").withDetail("redis", "UP").build());

        Map<String, Object> result = controller.health().block();

        assertEquals("UP", result.get("status"));
        assertTrue(result.containsKey("details"));
        Map<?, ?> details = (Map<?, ?>) result.get("details");
        assertEquals("UP", details.get("db"));
        assertEquals("UP", details.get("redis"));
        verify(healthEndpoint).health();
    }

    @Test
    void live_returnsStaticUpStatus() {
        Map<String, Object> result = controller.live();

        assertEquals(Map.of("status", "UP"), result);
    }

    @Test
    void ready_whenEndpointFails_returnsDownPayload() {
        when(healthEndpoint.health()).thenThrow(new IllegalStateException("boom"));

        Map<String, Object> result = controller.ready().block();

        assertEquals("DOWN", result.get("status"));
        assertTrue(result.containsKey("error"));
        assertFalse(String.valueOf(result.get("error")).isBlank());
    }
}
