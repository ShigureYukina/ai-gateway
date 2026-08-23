package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.ClientConfigView;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.ProviderConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.SceneConfigView;
import io.gateway.oss.core.contract.SystemConfigManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端配置导出组装支持类。
 */
public class AdminConfigExportSupport {

    private final GatewayConfigView gatewayConfigView;
    private final SystemConfigManager systemConfigManager;

    public AdminConfigExportSupport(GatewayConfigView gatewayConfigView,
                                    SystemConfigManager systemConfigManager) {
        this.gatewayConfigView = gatewayConfigView;
        this.systemConfigManager = systemConfigManager;
    }

    public Map<String, Object> buildExport() {
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("providers", exportProviders());
        export.put("routes", exportRoutes());
        export.put("scenes", exportScenes());
        export.put("clients", exportClients());
        export.put("system", exportSystem());
        export.put("pendingRestart", systemConfigManager.getPendingSystemKeys());
        return export;
    }

    private Map<String, Object> exportProviders() {
        Map<String, Object> providersOut = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends ProviderConfigView> entry : gatewayConfigView.getProviders().entrySet()) {
            Map<String, Object> view = new LinkedHashMap<>();
            ProviderConfigView config = entry.getValue();
            view.put("type", config.getType());
            view.put("baseUrl", config.getBaseUrl());
            view.put("apiKey", AdminBaseController.mask(config.getApiKey()));
            view.put("keys", AdminBaseController.maskKeys(config.getKeys()));
            view.put("keyWeights", config.getKeyWeights());
            view.put("timeout", config.getTimeout() != null ? config.getTimeout().toString() : null);
            view.put("enabled", config.isEnabled());
            view.put("models", config.getModels());
            providersOut.put(entry.getKey(), view);
        }
        return providersOut;
    }

    private Map<String, Object> exportRoutes() {
        Map<String, Object> routesOut = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends RouteConfigView> entry : gatewayConfigView.getRoutes().entrySet()) {
            Map<String, Object> view = new LinkedHashMap<>();
            RouteConfigView config = entry.getValue();
            view.put("provider", config.getProvider());
            view.put("upstreamModel", config.getUpstreamModel());
            view.put("scene", config.getScene());
            view.put("fallbackRoutes", config.getFallbackRoutes());
            view.put("weight", config.getWeight());
            view.put("enabled", config.isEnabled());
            routesOut.put(entry.getKey(), view);
        }
        return routesOut;
    }

    private Map<String, Object> exportScenes() {
        Map<String, Object> scenesOut = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends SceneConfigView> entry : gatewayConfigView.getScenes().entrySet()) {
            Map<String, Object> view = new LinkedHashMap<>();
            SceneConfigView config = entry.getValue();
            view.put("primaryRoute", config.getPrimaryRoute());
            view.put("fallbackRoutes", config.getFallbackRoutes());
            scenesOut.put(entry.getKey(), view);
        }
        return scenesOut;
    }

    private Map<String, Object> exportClients() {
        Map<String, Object> clientsOut = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends ClientConfigView> entry : gatewayConfigView.getClients().entrySet()) {
            Map<String, Object> view = new LinkedHashMap<>();
            ClientConfigView config = entry.getValue();
            view.put("enabled", config.isEnabled());
            view.put("allowedModels", config.getAllowedModels());
            view.put("allowedScenes", config.getAllowedScenes());
            view.put("modelScenes", config.getModelScenes());
            view.put("defaults", config.getDefaults());
            view.put("capabilities", config.getCapabilities());
            view.put("limits", config.getLimits());
            view.put("apiKeyMasked", AdminBaseController.mask(entry.getKey()));
            clientsOut.put(AdminBaseController.mask(entry.getKey()), view);
        }
        return clientsOut;
    }

    private Map<String, Object> exportSystem() {
        Map<String, Object> systemOut = new LinkedHashMap<>();
        systemOut.put("limit", gatewayConfigView.getLimit());
        systemOut.put("resilience", gatewayConfigView.getResilience());
        systemOut.put("pricing", gatewayConfigView.getPricing());
        systemOut.put("operational", gatewayConfigView.getOperational());
        return systemOut;
    }
}
