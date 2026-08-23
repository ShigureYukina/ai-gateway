package io.gateway.oss.core.upstream;

import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.contract.routing.ProviderApiKeyPool;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderKeySelectorTest {

    @Test
    void shouldRoundRobinAcrossConfiguredKeys() {
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), java.time.Clock.systemUTC());
        ProviderKeySelector selector = new ProviderKeySelector(tracker);
        ResolvedRoute route = pooledRoute();

        assertEquals(0, selector.select(route).keyIndex());
        assertEquals(1, selector.select(route).keyIndex());
        assertEquals(2, selector.select(route).keyIndex());
        assertEquals(0, selector.select(route).keyIndex());
    }

    @Test
    void shouldFallbackToLegacyApiKeyWhenPoolMissing() {
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), java.time.Clock.systemUTC());
        ProviderKeySelector selector = new ProviderKeySelector(tracker);
        ResolvedRoute route = legacyRoute();

        ProviderKeySelector.SelectedProviderKey selected = selector.select(route);
        assertEquals("legacy-key", selected.keyValue());
        assertEquals(0, selected.keyIndex());
    }

    @Test
    void shouldSkipTemporarilyOpenedKeySlots() {
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), java.time.Clock.systemUTC());
        ProviderKeySelector selector = new ProviderKeySelector(tracker);
        ResolvedRoute route = pooledRoute();

        tracker.recordRetryableFailure("openai-primary#key-0");
        tracker.recordRetryableFailure("openai-primary#key-0");

        ProviderKeySelector.SelectedProviderKey selected = selector.select(route);
        assertEquals(1, selected.keyIndex());
        assertEquals("key-b", selected.keyValue());
    }

    @Test
    void shouldPreferWeightedKeysWhenConfigured() {
        ProviderKeyResilienceTracker tracker = new ProviderKeyResilienceTracker(properties(), java.time.Clock.systemUTC());
        ProviderKeySelector selector = new ProviderKeySelector(tracker);
        ResolvedRoute route = weightedRoute();

        assertEquals(1, selector.select(route).keyIndex());
        assertEquals(0, selector.select(route).keyIndex());
        assertEquals(1, selector.select(route).keyIndex());
        assertEquals(1, selector.select(route).keyIndex());
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().setRetryableFailureThreshold(2);
        properties.getResilience().setFailureWindow(Duration.ofSeconds(30));
        properties.getResilience().setOpenDuration(Duration.ofSeconds(30));
        return properties;
    }

    private ResolvedRoute pooledRoute() {
        return new ResolvedRoute(
                "gpt-4o-mini",
                "openai-primary",
                "default-chat",
                "openai",
                "openai-compatible",
                "gpt-4o-mini",
                "http://localhost:18080",
                List.of("key-a", "key-b", "key-c"),
                "legacy-key",
                Duration.ofSeconds(1),
                2,
                List.of(),
                1
        );
    }

    private ResolvedRoute weightedRoute() {
        return new ResolvedRoute(
                "gpt-4o-mini",
                "openai-primary",
                "default-chat",
                "openai",
                "openai-compatible",
                "gpt-4o-mini",
                "http://localhost:18080",
                new ProviderApiKeyPool(List.of("key-a", "key-b"), List.of(1, 3)),
                List.of("key-a", "key-b"),
                "legacy-key",
                Duration.ofSeconds(1),
                2,
                List.of(),
                1
        );
    }

    private ResolvedRoute legacyRoute() {
        return new ResolvedRoute(
                "gpt-4o-mini",
                "openai-primary",
                "default-chat",
                "openai",
                "openai-compatible",
                "gpt-4o-mini",
                "http://localhost:18080",
                List.of(),
                "legacy-key",
                Duration.ofSeconds(1),
                2,
                List.of(),
                1
        );
    }
}
