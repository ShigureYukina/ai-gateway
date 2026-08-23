package io.gateway.oss.core.routing;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRouteResolverTest {

    @Test
    void shouldResolveProviderKeyPoolWhenKeysConfigured() {
        GatewayProperties properties = baseProperties();
        ProviderConfig openai = properties.getProviders().get("openai");
        openai.setKeys(List.of("key-a", "key-b"));
        openai.setKeyWeights(List.of(2, 5));
        ModelRouteResolver resolver = new ModelRouteResolver(properties);

        ResolvedRoute route = resolver.resolve("gpt-4o-mini", principal());

        assertEquals(List.of("key-a", "key-b"), route.providerApiKeys());
        assertEquals(List.of("key-a", "key-b"), route.providerApiKeyPool().keys());
        assertEquals(List.of(2, 5), route.providerApiKeyPool().weights());
        assertEquals("legacy-key", route.providerApiKey());
    }

    @Test
    void shouldKeepLegacyApiKeyWhenKeyPoolNotConfigured() {
        GatewayProperties properties = baseProperties();
        ModelRouteResolver resolver = new ModelRouteResolver(properties);

        ResolvedRoute route = resolver.resolve("gpt-4o-mini", principal());

        assertEquals(List.of(), route.providerApiKeys());
        assertEquals("legacy-key", route.providerApiKey());
    }

    @Test
    void shouldResolveConfiguredRouteWeight() {
        GatewayProperties properties = baseProperties();
        properties.getRoutes().get("gpt-4o-mini").setWeight(5);
        ModelRouteResolver resolver = new ModelRouteResolver(properties);

        ResolvedRoute route = resolver.resolve("gpt-4o-mini", principal());

        assertEquals(5, route.weight());
    }

    @Test
    void shouldRejectDisabledPrimaryRoute() {
        GatewayProperties properties = baseProperties();
        properties.getRoutes().get("gpt-4o-mini").setEnabled(false);
        ModelRouteResolver resolver = new ModelRouteResolver(properties);

        GatewayException error = assertThrows(GatewayException.class,
                () -> resolver.resolve("gpt-4o-mini", principal()));

        assertEquals("config_error", error.getCode());
    }

    @Test
    void shouldRejectDisabledFallbackRouteDuringResolution() {
        GatewayProperties properties = baseProperties();
        RouteConfig backup = new RouteConfig();
        backup.setProvider("openai");
        backup.setUpstreamModel("gpt-4o-mini-backup");
        backup.setEnabled(false);
        Map<String, RouteConfig> routes = new LinkedHashMap<>(properties.getRoutes());
        routes.put("backup-route", backup);
        properties.setRoutes(routes);
        properties.getRoutes().get("gpt-4o-mini").setFallbackRoutes(List.of("backup-route"));
        ModelRouteResolver resolver = new ModelRouteResolver(properties);

        GatewayException error = assertThrows(GatewayException.class,
                () -> resolver.resolve("gpt-4o-mini", principal()));

        assertEquals("config_error", error.getCode());
    }

    private GatewayProperties baseProperties() {
        GatewayProperties properties = new GatewayProperties();
        ProviderConfig provider = new ProviderConfig();
        provider.setBaseUrl("http://localhost:18080");
        provider.setApiKey("legacy-key");

        RouteConfig route = new RouteConfig();
        route.setProvider("openai");
        route.setUpstreamModel("gpt-4o-mini");

        Map<String, ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("openai", provider);
        properties.setProviders(providers);

        Map<String, RouteConfig> routes = new LinkedHashMap<>();
        routes.put("gpt-4o-mini", route);
        properties.setRoutes(routes);

        return properties;
    }

    private ClientPrincipal principal() {
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.getAllowedModels().add("gpt-4o-mini");
        return new ClientPrincipal("demo-client", clientConfig);
    }
}
