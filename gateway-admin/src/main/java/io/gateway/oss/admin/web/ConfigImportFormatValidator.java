package io.gateway.oss.admin.web;

import io.gateway.oss.core.error.GatewayException;
import io.gateway.oss.core.security.BaseUrlValidator;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置导入格式校验工具。
 */
public final class ConfigImportFormatValidator {

    private ConfigImportFormatValidator() {
    }

    public static void validateImportPayload(Map<String, Object> providers,
                                             Map<String, Object> routes,
                                             Map<String, Object> scenes,
                                             Map<String, Object> clients,
                                             Set<String> existingProviderIds,
                                             Set<String> existingRouteIds,
                                             Set<String> existingSceneIds,
                                             BaseUrlValidator baseUrlValidator) {
        List<String> errors = new ArrayList<>();

        Set<String> importedProviderIds = providers.keySet();
        Set<String> importedRouteIds = routes.keySet();
        Set<String> importedSceneIds = scenes.keySet();

        providers.forEach((providerId, value) -> {
            if (value instanceof Map<?, ?> providerMap && hasText(providerMap.get("baseUrl"))) {
                try {
                    baseUrlValidator.validate(String.valueOf(providerMap.get("baseUrl")));
                } catch (GatewayException ex) {
                    errors.add("providers." + providerId + ".baseUrl: " + ex.getMessage());
                }
            }
        });

        routes.forEach((routeId, value) -> {
            if (!(value instanceof Map<?, ?> routeMap)) {
                return;
            }
            if (hasText(routeMap.get("provider"))) {
                String providerId = String.valueOf(routeMap.get("provider"));
                if (!existingProviderIds.contains(providerId) && !importedProviderIds.contains(providerId)) {
                    errors.add("routes." + routeId + ".provider references missing provider: " + providerId);
                }
            }
            if (hasText(routeMap.get("scene"))) {
                String sceneId = String.valueOf(routeMap.get("scene"));
                if (!existingSceneIds.contains(sceneId) && !importedSceneIds.contains(sceneId)) {
                    errors.add("routes." + routeId + ".scene references missing scene: " + sceneId);
                }
            }
            if (routeMap.get("fallbackRoutes") instanceof List<?> fallbackRoutes) {
                fallbackRoutes.stream()
                        .filter(ConfigImportFormatValidator::hasText)
                        .map(String::valueOf)
                        .forEach(fallbackRouteId -> {
                            if (!existingRouteIds.contains(fallbackRouteId) && !importedRouteIds.contains(fallbackRouteId)) {
                                errors.add("routes." + routeId + ".fallbackRoutes references missing route: " + fallbackRouteId);
                            }
                        });
            }
        });

        scenes.forEach((sceneId, value) -> {
            if (!(value instanceof Map<?, ?> sceneMap)) {
                return;
            }
            if (hasText(sceneMap.get("primaryRoute"))) {
                String primaryRouteId = String.valueOf(sceneMap.get("primaryRoute"));
                if (!existingRouteIds.contains(primaryRouteId) && !importedRouteIds.contains(primaryRouteId)) {
                    errors.add("scenes." + sceneId + ".primaryRoute references missing route: " + primaryRouteId);
                }
            }
            if (sceneMap.get("fallbackRoutes") instanceof List<?> fallbackRoutes) {
                fallbackRoutes.stream()
                        .filter(ConfigImportFormatValidator::hasText)
                        .map(String::valueOf)
                        .forEach(fallbackRouteId -> {
                            if (!existingRouteIds.contains(fallbackRouteId) && !importedRouteIds.contains(fallbackRouteId)) {
                                errors.add("scenes." + sceneId + ".fallbackRoutes references missing route: " + fallbackRouteId);
                            }
                        });
            }
        });

        clients.forEach((clientId, value) -> {
            if (!(value instanceof Map<?, ?> clientMap)) {
                return;
            }
            validateClientSceneReferences(errors, existingSceneIds, importedSceneIds, clientId, clientMap);
        });

        if (!errors.isEmpty()) {
            throw invalidImport(String.join("; ", errors));
        }
    }

    public static List<Integer> parseIntegerList(List<?> values, String fieldPath) {
        List<Integer> parsed = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            parsed.add(parseInteger(values.get(i), fieldPath + "[" + i + "]"));
        }
        return parsed;
    }

    public static Integer parsePositiveInteger(Object value, String fieldPath) {
        Integer parsed = parseInteger(value, fieldPath);
        if (parsed <= 0) {
            throw invalidImport(fieldPath + ": must be a positive integer");
        }
        return parsed;
    }

    public static Integer parseInteger(Object value, String fieldPath) {
        if (value == null) {
            throw invalidImport(fieldPath + ": must be an integer");
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw invalidImport(fieldPath + ": must be an integer");
        }
    }

    public static Duration parseDuration(Object value, String fieldPath) {
        if (!hasText(value)) {
            throw invalidImport(fieldPath + ": must be a valid ISO-8601 duration");
        }
        try {
            return Duration.parse(String.valueOf(value));
        } catch (DateTimeParseException ex) {
            throw invalidImport(fieldPath + ": must be a valid ISO-8601 duration");
        }
    }

    public static boolean hasText(Object value) {
        if (value == null) {
            return false;
        }
        String str = String.valueOf(value);
        return !str.isBlank() && !"null".equalsIgnoreCase(str);
    }

    public static GatewayException invalidImport(String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, "invalid_config_import", "Config import validation failed: " + message);
    }

    private static void validateClientSceneReferences(List<String> errors,
                                                      Set<String> existingSceneIds,
                                                      Set<String> importedSceneIds,
                                                      String clientId,
                                                      Map<?, ?> clientMap) {
        if (clientMap.get("allowedScenes") instanceof List<?> allowedScenes) {
            allowedScenes.stream()
                    .filter(ConfigImportFormatValidator::hasText)
                    .map(String::valueOf)
                    .forEach(sceneId -> addMissingSceneError(errors, existingSceneIds, importedSceneIds, clientId,
                            ".allowedScenes references missing scene: ", sceneId));
        }
        if (clientMap.get("allowedScenes") instanceof Set<?> allowedScenesSet) {
            allowedScenesSet.stream()
                    .filter(ConfigImportFormatValidator::hasText)
                    .map(String::valueOf)
                    .forEach(sceneId -> addMissingSceneError(errors, existingSceneIds, importedSceneIds, clientId,
                            ".allowedScenes references missing scene: ", sceneId));
        }
        if (clientMap.get("modelScenes") instanceof Map<?, ?> modelScenes) {
            modelScenes.forEach((model, scene) -> {
                if (hasText(model) && hasText(scene)) {
                    String sceneId = String.valueOf(scene);
                    if (!existingSceneIds.contains(sceneId) && !importedSceneIds.contains(sceneId)) {
                        errors.add("clients." + clientId + ".modelScenes[" + model + "] references missing scene: " + sceneId);
                    }
                }
            });
        }
        if (clientMap.get("defaults") instanceof Map<?, ?> defaults && hasText(defaults.get("scene"))) {
            addMissingSceneError(errors, existingSceneIds, importedSceneIds, clientId,
                    ".defaults.scene references missing scene: ", String.valueOf(defaults.get("scene")));
        }
    }

    private static void addMissingSceneError(List<String> errors,
                                             Set<String> existingSceneIds,
                                             Set<String> importedSceneIds,
                                             String clientId,
                                             String suffix,
                                             String sceneId) {
        if (!existingSceneIds.contains(sceneId) && !importedSceneIds.contains(sceneId)) {
            errors.add("clients." + clientId + suffix + sceneId);
        }
    }
}
