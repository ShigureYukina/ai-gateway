package io.gateway.oss.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteConfigTest {

    @Test
    void defaultValuesMatchExpectedConfiguration() {
        RouteConfig config = new RouteConfig();

        assertTrue(config.isEnabled());
        assertEquals(1, config.getWeight());
        assertEquals("round-robin", config.getStrategy());
    }

    @Test
    void isConcreteRouteReturnsTrueWhenProviderAndUpstreamModelAreSet() {
        RouteConfig config = new RouteConfig();
        config.setProvider("openai");
        config.setUpstreamModel("gpt-4o");

        assertTrue(config.isConcreteRoute());
    }

    @Test
    void isConcreteRouteReturnsFalseWhenProviderIsNull() {
        RouteConfig config = new RouteConfig();
        config.setUpstreamModel("gpt-4o");

        assertFalse(config.isConcreteRoute());
    }

    @Test
    void isConcreteRouteReturnsFalseWhenUpstreamModelsAreEmpty() {
        RouteConfig config = new RouteConfig();
        config.setProvider("openai");
        config.setUpstreamModels(List.of());

        assertFalse(config.isConcreteRoute());
    }

    @Test
    void settersAndGettersRoundTripAllFields() {
        RouteConfig config = new RouteConfig();
        List<String> upstreamModels = List.of("gpt-4o", "gpt-4.1");
        List<String> fallbackRoutes = List.of("fallback-a", "fallback-b");

        config.setProvider("openai");
        config.setUpstreamModel("gpt-4o");
        config.setUpstreamModels(upstreamModels);
        config.setScene("chat");
        config.setStrategy("weighted");
        config.setFallbackRoutes(fallbackRoutes);
        config.setWeight(3);
        config.setEnabled(false);

        assertEquals("openai", config.getProvider());
        assertEquals("gpt-4o", config.getUpstreamModel());
        assertEquals(upstreamModels, config.getUpstreamModels());
        assertEquals("chat", config.getScene());
        assertEquals("weighted", config.getStrategy());
        assertEquals(fallbackRoutes, config.getFallbackRoutes());
        assertEquals(3, config.getWeight());
        assertFalse(config.isEnabled());
    }
}
