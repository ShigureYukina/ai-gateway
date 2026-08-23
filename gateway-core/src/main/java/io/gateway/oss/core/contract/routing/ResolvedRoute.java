package io.gateway.oss.core.contract.routing;

import java.time.Duration;
import java.util.List;

public record ResolvedRoute(
        String requestedModel,
        String routeId,
        String scene,
        String provider,
        String providerType,
        String upstreamModel,
        String baseUrl,
        ProviderApiKeyPool providerApiKeyPool,
        List<String> providerApiKeys,
        String providerApiKey,
        Duration timeout,
        int maxAttempts,
        List<String> fallbackRouteIds,
        int weight
) {

    public ResolvedRoute {
        providerApiKeyPool = providerApiKeyPool == null ? new ProviderApiKeyPool(List.of(), List.of()) : providerApiKeyPool;
        providerApiKeys = providerApiKeys == null ? List.of() : List.copyOf(providerApiKeys);
        fallbackRouteIds = fallbackRouteIds == null ? List.of() : List.copyOf(fallbackRouteIds);
        if (weight <= 0) {
            weight = 1;
        }
    }

    public ResolvedRoute(String requestedModel,
                         String routeId,
                         String scene,
                         String provider,
                         String providerType,
                         String upstreamModel,
                         String baseUrl,
                         List<String> providerApiKeys,
                         String providerApiKey,
                         Duration timeout,
                         int maxAttempts,
                         List<String> fallbackRouteIds,
                         int weight) {
        this(
                requestedModel,
                routeId,
                scene,
                provider,
                providerType,
                upstreamModel,
                baseUrl,
                new ProviderApiKeyPool(providerApiKeys, List.of()),
                providerApiKeys,
                providerApiKey,
                timeout,
                maxAttempts,
                fallbackRouteIds,
                weight
        );
    }

    public ResolvedRoute(String requestedModel,
                         String routeId,
                         String scene,
                         String provider,
                         String providerType,
                         String upstreamModel,
                         String baseUrl,
                         String providerApiKey,
                         Duration timeout,
                         int maxAttempts,
                         List<String> fallbackRouteIds) {
        this(
                requestedModel,
                routeId,
                scene,
                provider,
                providerType,
                upstreamModel,
                baseUrl,
                new ProviderApiKeyPool(providerApiKey == null ? List.of() : List.of(providerApiKey), List.of(1)),
                providerApiKey == null ? List.of() : List.of(providerApiKey),
                providerApiKey,
                timeout,
                maxAttempts,
                fallbackRouteIds,
                1
        );
    }

    public ResolvedRoute withProviderApiKey(String selectedProviderApiKey) {
        return new ResolvedRoute(
                requestedModel,
                routeId,
                scene,
                provider,
                providerType,
                upstreamModel,
                baseUrl,
                providerApiKeyPool,
                providerApiKeys,
                selectedProviderApiKey,
                timeout,
                maxAttempts,
                fallbackRouteIds,
                weight
        );
    }

    public ResolvedRoute withWeight(int weight) {
        return new ResolvedRoute(
                requestedModel,
                routeId,
                scene,
                provider,
                providerType,
                upstreamModel,
                baseUrl,
                providerApiKeyPool,
                providerApiKeys,
                providerApiKey,
                timeout,
                maxAttempts,
                fallbackRouteIds,
                weight
        );
    }
}
