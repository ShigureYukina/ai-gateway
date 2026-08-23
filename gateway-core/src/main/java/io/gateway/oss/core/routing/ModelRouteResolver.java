package io.gateway.oss.core.routing;

import io.gateway.oss.core.contract.routing.ProviderApiKeyPool;
import io.gateway.oss.core.contract.routing.ResolvedRoute;
import io.gateway.oss.core.contract.security.ClientPrincipal;
import io.gateway.oss.core.config.GatewayProperties;
import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.error.GatewayException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class ModelRouteResolver {

    private final GatewayProperties properties;

    private final Cache<String, ResolvedRoute> routeCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(500)
            .build();

    public ModelRouteResolver(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * Clear route cache for tests.
     */
    public void resetCache() {
        routeCache.invalidateAll();
    }

    public ResolvedRoute resolve(String modelAlias, ClientPrincipal principal) {
        String cacheKey = modelAlias + ":" + (principal != null ? principal.clientId() : "");
        ResolvedRoute cached = routeCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        var routeConfig = properties.getRoutes().get(modelAlias);
        if (routeConfig == null) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "unknown_model", "Unknown model alias: " + modelAlias);
        }
        requireRouteEnabled(modelAlias, routeConfig);

        String scene = resolveScene(modelAlias, routeConfig, principal);
        String primaryRouteId = resolvePrimaryRouteId(modelAlias, routeConfig, scene);
        var primaryRoute = requireConcreteRoute(primaryRouteId);
        List<String> fallbackRouteIds = resolveFallbackRouteIds(routeConfig, scene, primaryRouteId);
        ResolvedRoute result = buildResolvedRoute(modelAlias, primaryRouteId, scene, primaryRoute, fallbackRouteIds);
        routeCache.put(cacheKey, result);
        return result;
    }

    public ResolvedRoute resolveFallback(String requestedModel, String routeId) {
        var route = requireConcreteRoute(routeId);
        return buildResolvedRoute(requestedModel, routeId, route.getScene(), route, List.of());
    }

    private ResolvedRoute buildResolvedRoute(String requestedModel,
                                              String routeId,
                                              String scene,
                                              RouteConfig route,
                                              List<String> fallbackRouteIds) {
        var provider = properties.getProviders().get(route.getProvider());
        if (provider == null) {
            throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "config_error", "Provider is not configured");
        }

        return new ResolvedRoute(
                requestedModel,
                routeId,
                scene,
                route.getProvider(),
                provider.getType(),
                route.getUpstreamModel(),
                provider.getBaseUrl(),
                resolveProviderKeyPool(provider),
                resolveProviderKeys(provider),
                provider.getApiKey(),
                provider.getTimeout(),
                properties.getResilience().getMaxAttempts(),
                fallbackRouteIds,
                route.getWeight()
        );
    }

    private ProviderApiKeyPool resolveProviderKeyPool(ProviderConfig provider) {
        List<String> keys = resolveProviderKeys(provider);
        if (keys.isEmpty()) {
            if (!hasText(provider.getApiKey())) {
                return new ProviderApiKeyPool(List.of(), List.of());
            }
            return new ProviderApiKeyPool(List.of(provider.getApiKey()), List.of(1));
        }

        List<Integer> rawWeights = provider.getKeyWeights();
        List<Integer> weights = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            int weight = 1;
            if (rawWeights != null && i < rawWeights.size() && rawWeights.get(i) != null && rawWeights.get(i) > 0) {
                weight = rawWeights.get(i);
            }
            weights.add(weight);
        }
        return new ProviderApiKeyPool(keys, weights);
    }

    private List<String> resolveProviderKeys(ProviderConfig provider) {
        if (provider.getKeys() == null || provider.getKeys().isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String key : provider.getKeys()) {
            if (hasText(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private String resolveScene(String modelAlias, RouteConfig routeConfig, ClientPrincipal principal) {
        // 动态注册用户（无静态 client 配置）跳过 client scene 检查
        if (principal.config() != null) {
            String clientScene = principal.config().getModelScenes().get(modelAlias);
            if (hasText(clientScene)) {
                return clientScene;
            }
        }
        if (hasText(routeConfig.getScene())) {
            return routeConfig.getScene();
        }
        if (principal.config() != null) {
            return principal.config().getDefaults().getScene();
        }
        return null;
    }

    private String resolvePrimaryRouteId(String modelAlias, RouteConfig routeConfig, String scene) {
        if (hasText(scene)) {
            var sceneConfig = properties.getScenes().get(scene);
            if (sceneConfig == null) {
                throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "config_error", "Scene is not configured: " + scene);
            }
            return sceneConfig.getPrimaryRoute();
        }
        if (routeConfig.isConcreteRoute()) {
            return modelAlias;
        }
        throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "config_error", "Route is not fully configured: " + modelAlias);
    }

    private List<String> resolveFallbackRouteIds(RouteConfig routeConfig, String scene, String primaryRouteId) {
        Set<String> routeIds = new LinkedHashSet<>();
        if (scene != null && !scene.isBlank()) {
            var sceneConfig = properties.getScenes().get(scene);
            if (sceneConfig != null) {
                routeIds.addAll(sceneConfig.getFallbackRoutes());
            }
        }
        routeIds.addAll(routeConfig.getFallbackRoutes());
        routeIds.remove(primaryRouteId);

        List<String> validated = new ArrayList<>();
        for (String routeId : routeIds) {
            if (!hasText(routeId)) {
                continue;
            }
            requireConcreteRoute(routeId);
            validated.add(routeId);
        }
        return validated;
    }

    private RouteConfig requireConcreteRoute(String routeId) {
        var route = properties.getRoutes().get(routeId);
        if (route == null) {
            throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "config_error", "Route is not configured: " + routeId);
        }
        requireRouteEnabled(routeId, route);
        if (!route.isConcreteRoute()) {
            throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "config_error", "Route is not concrete: " + routeId);
        }
        return route;
    }

    private void requireRouteEnabled(String routeId, RouteConfig route) {
        if (!route.isEnabled()) {
            throw new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, "config_error", "Route is disabled: " + routeId);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
