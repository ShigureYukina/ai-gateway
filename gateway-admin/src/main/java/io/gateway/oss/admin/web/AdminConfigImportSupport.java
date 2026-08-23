package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.ClientCapabilities;
import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.ClientDefaults;
import io.gateway.oss.core.config.ClientLimits;
import io.gateway.oss.core.config.LimitConfig;
import io.gateway.oss.core.config.OperationalConfig;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.SceneConfig;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.ProviderConfigView;
import io.gateway.oss.core.security.BaseUrlValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端配置导入的校验与解析支持类。
 */
public class AdminConfigImportSupport {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final GatewayConfigView gatewayConfigView;
    private final ObjectMapper objectMapper;
    private final BaseUrlValidator baseUrlValidator;

    public AdminConfigImportSupport(GatewayConfigView gatewayConfigView,
                                    ObjectMapper objectMapper,
                                    BaseUrlValidator baseUrlValidator) {
        this.gatewayConfigView = gatewayConfigView;
        this.objectMapper = objectMapper;
        this.baseUrlValidator = baseUrlValidator;
    }

    public AdminImportedConfig parseImport(Map<String, Object> body) {
        Map<String, Object> providers = extractMap(body, "providers");
        Map<String, Object> routes = extractMap(body, "routes");
        Map<String, Object> scenes = extractMap(body, "scenes");
        Map<String, Object> clients = extractMap(body, "clients");
        Map<String, Object> system = extractMap(body, "system");

        ConfigImportFormatValidator.validateImportPayload(
                providers,
                routes,
                scenes,
                clients,
                new HashSet<>(gatewayConfigView.getProviders().keySet()),
                new HashSet<>(gatewayConfigView.getRoutes().keySet()),
                new HashSet<>(gatewayConfigView.getScenes().keySet()),
                baseUrlValidator);

        return new AdminImportedConfig(
                parseProviders(providers),
                parseRoutes(routes),
                parseScenes(scenes),
                parseClients(clients),
                parseSystemSection(system, "limit", LimitConfig.class),
                parseSystemSection(system, "resilience", ResilienceConfig.class),
                parseSystemSection(system, "pricing", PricingConfig.class),
                parseSystemSection(system, "operational", OperationalConfig.class),
                providers.size() + routes.size() + scenes.size() + clients.size() + system.size()
        );
    }

    private Map<String, Object> extractMap(Map<String, Object> body, String key) {
        return objectMapper.convertValue(body.getOrDefault(key, Map.of()), MAP_TYPE);
    }

    private <T> T parseSystemSection(Map<String, Object> system, String key, Class<T> type) {
        if (!(system.get(key) instanceof Map<?, ?> sectionMap) || sectionMap.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(sectionMap, type);
    }

    private Map<String, ProviderConfig> parseProviders(Map<String, Object> providers) {
        Map<String, ProviderConfig> parsed = new LinkedHashMap<>();
        providers.forEach((providerId, value) -> {
            if (!(value instanceof Map<?, ?> providerMap)) {
                return;
            }
            ProviderConfig cfg = new ProviderConfig();
            ProviderConfigView existing = gatewayConfigView.getProviders().get(providerId);
            if (hasText(providerMap.get("type"))) {
                cfg.setType(String.valueOf(providerMap.get("type")));
            }
            if (hasText(providerMap.get("baseUrl"))) {
                cfg.setBaseUrl(String.valueOf(providerMap.get("baseUrl")));
            }
            baseUrlValidator.validate(cfg.getBaseUrl());
            if (hasText(providerMap.get("apiKey"))) {
                String importedApiKey = String.valueOf(providerMap.get("apiKey"));
                cfg.setApiKey(isMaskedValue(importedApiKey) && existing != null ? existing.getApiKey() : importedApiKey);
            } else if (existing != null) {
                cfg.setApiKey(existing.getApiKey());
            }
            if (providerMap.get("keys") instanceof List<?> keys && !keys.isEmpty()) {
                boolean allMasked = keys.stream().allMatch(item -> item instanceof String s && isMaskedValue(s));
                if (allMasked && existing != null) {
                    cfg.setKeys(existing.getKeys());
                } else {
                    cfg.setKeys(keys.stream().filter(this::hasText).map(String::valueOf).toList());
                }
            } else if (existing != null) {
                cfg.setKeys(existing.getKeys());
            }
            if (providerMap.get("keyWeights") instanceof List<?> keyWeights && !keyWeights.isEmpty()) {
                cfg.setKeyWeights(ConfigImportFormatValidator.parseIntegerList(keyWeights, "providers." + providerId + ".keyWeights"));
            } else if (existing != null) {
                cfg.setKeyWeights(existing.getKeyWeights());
            }
            if (providerMap.containsKey("timeout")) {
                cfg.setTimeout(ConfigImportFormatValidator.parseDuration(providerMap.get("timeout"), "providers." + providerId + ".timeout"));
            }
            if (providerMap.containsKey("enabled")) {
                cfg.setEnabled(Boolean.TRUE.equals(providerMap.get("enabled")));
            }
            if (providerMap.get("models") instanceof List<?> models && !models.isEmpty()) {
                cfg.setModels(models.stream().filter(this::hasText).map(String::valueOf).toList());
            } else if (existing != null) {
                cfg.setModels(existing.getModels());
            }
            parsed.put(providerId, cfg);
        });
        return parsed;
    }

    private Map<String, RouteConfig> parseRoutes(Map<String, Object> routes) {
        Map<String, RouteConfig> parsed = new LinkedHashMap<>();
        routes.forEach((routeId, value) -> {
            if (!(value instanceof Map<?, ?> routeMap)) {
                return;
            }
            RouteConfig cfg = new RouteConfig();
            if (routeMap.containsKey("provider")) {
                cfg.setProvider(String.valueOf(routeMap.get("provider")));
            }
            if (routeMap.containsKey("upstreamModel")) {
                cfg.setUpstreamModel(String.valueOf(routeMap.get("upstreamModel")));
            }
            if (routeMap.containsKey("scene")) {
                cfg.setScene(String.valueOf(routeMap.get("scene")));
            }
            if (routeMap.get("fallbackRoutes") instanceof List<?> fallbackRoutes) {
                cfg.setFallbackRoutes(fallbackRoutes.stream().filter(this::hasText).map(String::valueOf).toList());
            }
            if (routeMap.containsKey("weight")) {
                cfg.setWeight(ConfigImportFormatValidator.parsePositiveInteger(routeMap.get("weight"), "routes." + routeId + ".weight"));
            }
            if (routeMap.containsKey("enabled")) {
                cfg.setEnabled(Boolean.TRUE.equals(routeMap.get("enabled")));
            }
            parsed.put(routeId, cfg);
        });
        return parsed;
    }

    private Map<String, SceneConfig> parseScenes(Map<String, Object> scenes) {
        Map<String, SceneConfig> parsed = new LinkedHashMap<>();
        scenes.forEach((sceneId, value) -> {
            if (!(value instanceof Map<?, ?> sceneMap)) {
                return;
            }
            SceneConfig cfg = new SceneConfig();
            if (sceneMap.containsKey("primaryRoute")) {
                cfg.setPrimaryRoute(String.valueOf(sceneMap.get("primaryRoute")));
            }
            if (sceneMap.get("fallbackRoutes") instanceof List<?> fallbackRoutes) {
                cfg.setFallbackRoutes(fallbackRoutes.stream().map(String::valueOf).toList());
            }
            parsed.put(sceneId, cfg);
        });
        return parsed;
    }

    private Map<String, ClientConfig> parseClients(Map<String, Object> clients) {
        Map<String, ClientConfig> parsed = new LinkedHashMap<>();
        clients.forEach((clientKey, value) -> {
            if (!(value instanceof Map<?, ?> clientMap)) {
                return;
            }
            String resolvedClientKey = resolveImportedClientKey(String.valueOf(clientKey));
            ClientConfig cfg = new ClientConfig();
            if (clientMap.containsKey("enabled")) {
                cfg.setEnabled(Boolean.TRUE.equals(clientMap.get("enabled")));
            }
            if (clientMap.get("allowedModels") instanceof List<?> allowedModels && !allowedModels.isEmpty()) {
                cfg.setAllowedModels(allowedModels.stream().filter(this::hasText).map(String::valueOf).collect(java.util.stream.Collectors.toSet()));
            }
            if (clientMap.get("allowedModels") instanceof Set<?> allowedModelSet && !allowedModelSet.isEmpty()) {
                cfg.setAllowedModels(allowedModelSet.stream().filter(this::hasText).map(String::valueOf).collect(java.util.stream.Collectors.toSet()));
            }
            if (clientMap.get("allowedScenes") instanceof List<?> allowedScenes && !allowedScenes.isEmpty()) {
                cfg.setAllowedScenes(allowedScenes.stream().filter(this::hasText).map(String::valueOf).collect(java.util.stream.Collectors.toSet()));
            }
            if (clientMap.get("allowedScenes") instanceof Set<?> allowedSceneSet && !allowedSceneSet.isEmpty()) {
                cfg.setAllowedScenes(allowedSceneSet.stream().filter(this::hasText).map(String::valueOf).collect(java.util.stream.Collectors.toSet()));
            }
            if (clientMap.get("modelScenes") instanceof Map<?, ?> modelScenes && !modelScenes.isEmpty()) {
                Map<String, String> parsedModelScenes = new LinkedHashMap<>();
                modelScenes.forEach((model, scene) -> {
                    if (hasText(model) && hasText(scene)) {
                        parsedModelScenes.put(String.valueOf(model), String.valueOf(scene));
                    }
                });
                if (!parsedModelScenes.isEmpty()) {
                    cfg.setModelScenes(parsedModelScenes);
                }
            }
            if (clientMap.get("defaults") instanceof Map<?, ?> defaults && !defaults.isEmpty()) {
                cfg.setDefaults(objectMapper.convertValue(defaults, ClientDefaults.class));
            }
            if (clientMap.get("capabilities") instanceof Map<?, ?> capabilities && !capabilities.isEmpty()) {
                cfg.setCapabilities(objectMapper.convertValue(capabilities, ClientCapabilities.class));
            }
            if (clientMap.get("limits") instanceof Map<?, ?> limits && !limits.isEmpty()) {
                cfg.setLimits(objectMapper.convertValue(limits, ClientLimits.class));
            }
            parsed.put(resolvedClientKey, cfg);
        });
        return parsed;
    }

    private String resolveImportedClientKey(String importedKey) {
        if (!isMaskedValue(importedKey)) {
            return importedKey;
        }
        List<String> matches = gatewayConfigView.getClients().keySet().stream()
                .filter(existing -> importedKey.equals(AdminBaseController.mask(existing)))
                .toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        throw ConfigImportFormatValidator.invalidImport("clients." + importedKey + " could not be resolved to a unique existing client key");
    }

    private boolean isMaskedValue(String value) {
        return value != null && value.startsWith("****");
    }

    private boolean hasText(Object value) {
        return ConfigImportFormatValidator.hasText(value);
    }
}
