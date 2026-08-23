package io.gateway.oss.core.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayPropertiesTest {

    @Test
    void defaultSubConfigurationsAreInitialized() {
        GatewayProperties properties = new GatewayProperties();

        assertNotNull(properties.getProviders());
        assertNotNull(properties.getRoutes());
        assertNotNull(properties.getScenes());
        assertNotNull(properties.getClients());
        assertNotNull(properties.getLimit());
        assertNotNull(properties.getConcurrentLimit());
        assertNotNull(properties.getTracing());
        assertNotNull(properties.getResilience());
        assertNotNull(properties.getSharedState());
        assertNotNull(properties.getPricing());
        assertNotNull(properties.getLoadBalancer());
        assertNotNull(properties.getSync());
        assertNotNull(properties.getProviderHealth());
        assertNotNull(properties.getAuth());
        assertNotNull(properties.getOperational());
        assertNotNull(properties.getStore());
        assertTrue(properties.getProviders().isEmpty());
        assertTrue(properties.getRoutes().isEmpty());
        assertTrue(properties.getScenes().isEmpty());
        assertTrue(properties.getClients().isEmpty());
    }

    @Test
    void setProvidersWithNullDefaultsToEmptyMap() {
        GatewayProperties properties = new GatewayProperties();

        properties.setProviders(null);

        assertTrue(properties.getProviders().isEmpty());
    }

    @Test
    void setRoutesWithNullDefaultsToEmptyMap() {
        GatewayProperties properties = new GatewayProperties();

        properties.setRoutes(null);

        assertTrue(properties.getRoutes().isEmpty());
    }

    @Test
    void returnedMapsAreImmutableCopies() {
        GatewayProperties properties = new GatewayProperties();
        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put("provider-a", new ProviderConfig());
        Map<String, RouteConfig> routes = new HashMap<>();
        routes.put("route-a", new RouteConfig());

        properties.setProviders(providers);
        properties.setRoutes(routes);

        providers.put("provider-b", new ProviderConfig());
        routes.put("route-b", new RouteConfig());

        assertEquals(1, properties.getProviders().size());
        assertEquals(1, properties.getRoutes().size());
        assertThrows(UnsupportedOperationException.class,
                () -> properties.getProviders().put("provider-c", new ProviderConfig()));
        assertThrows(UnsupportedOperationException.class,
                () -> properties.getRoutes().put("route-c", new RouteConfig()));
    }

    @Test
    void storeConfigHasExpectedDefaultValues() {
        StoreConfig storeConfig = new StoreConfig();

        assertEquals(Backend.REDIS, storeConfig.getRateLimiter());
        assertEquals(Backend.REDIS, storeConfig.getTpm());
        assertEquals(Backend.POSTGRESQL, storeConfig.getUsage());
        assertEquals(Backend.POSTGRESQL, storeConfig.getCost());
        assertEquals(Backend.REDIS, storeConfig.getRouteState());
        assertEquals(Backend.REDIS, storeConfig.getProviderState());
        assertEquals(Backend.POSTGRESQL, storeConfig.getAggregateMetrics());
        assertEquals(Backend.POSTGRESQL, storeConfig.getConfig());
        assertEquals(Backend.POSTGRESQL, storeConfig.getTrace());
    }

    @Test
    void traceConfigHasExpectedDefaultValues() {
        TraceConfig traceConfig = new TraceConfig();

        assertEquals(false, traceConfig.isEnabled());
        assertEquals(16384, traceConfig.getMaxBodySize());
        assertEquals(1.0, traceConfig.getSampleRate());
    }
}
