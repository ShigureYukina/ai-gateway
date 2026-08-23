package io.gateway.oss.admin.web;

import io.gateway.oss.core.contract.ClientConfigView;
import io.gateway.oss.core.contract.GatewayConfigView;
import io.gateway.oss.core.contract.ProviderConfigView;
import io.gateway.oss.core.contract.RouteConfigView;
import io.gateway.oss.core.contract.SceneConfigView;
import io.gateway.oss.core.web.support.ConfigMaskingSupport;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 负责组装配置快照响应，避免 Controller 承担数据映射细节。
 */
class ConfigSnapshotAssembler {

    private final GatewayConfigView gatewayConfigView;
    private final ConfigMaskingSupport maskingSupport;

    ConfigSnapshotAssembler(GatewayConfigView gatewayConfigView,
                            ConfigMaskingSupport maskingSupport) {
        this.gatewayConfigView = gatewayConfigView;
        this.maskingSupport = maskingSupport;
    }

    public SnapshotResponse buildSnapshot() {
        Map<String, ProviderSnapshot> providers = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends ProviderConfigView> entry : gatewayConfigView.getProviders().entrySet()) {
            ProviderConfigView cfg = entry.getValue();
            providers.put(entry.getKey(), new ProviderSnapshot(
                    cfg.getType(),
                    cfg.getBaseUrl(),
                    maskingSupport.mask(cfg.getApiKey()),
                    maskingSupport.maskKeys(cfg.getKeys()),
                    cfg.getKeyWeights(),
                    cfg.getTimeout(),
                    cfg.isEnabled()
            ));
        }

        Map<String, RouteConfigView> routes = new LinkedHashMap<>(gatewayConfigView.getRoutes());
        Map<String, SceneConfigView> scenes = new LinkedHashMap<>(gatewayConfigView.getScenes());

        Map<String, ClientView> clients = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends ClientConfigView> entry : gatewayConfigView.getClients().entrySet()) {
            ClientConfigView cfg = entry.getValue();
            clients.put(maskingSupport.maskClientKey(entry.getKey()), new ClientView(
                    cfg.isEnabled(),
                    cfg.getAllowedModels(),
                    cfg.getAllowedScenes(),
                    cfg.getModelScenes(),
                    new ClientDefaultsView(
                            cfg.getDefaults().getScene(),
                            cfg.getDefaults().getTemperature(),
                            cfg.getDefaults().getMaxTokens()
                    ),
                    new ClientCapabilitiesView(cfg.getCapabilities().isStreaming()),
                    new ClientLimitsView(
                            cfg.getLimits().getMaxTokens(),
                            cfg.getLimits().getDailyTokens(),
                            cfg.getLimits().getDailyCost(),
                            cfg.getLimits().getMonthlyTokens(),
                            cfg.getLimits().getMonthlyCost()
                    )
            ));
        }

        Map<String, Object> system = new LinkedHashMap<>();
        system.put("limit", gatewayConfigView.getLimit());
        system.put("resilience", gatewayConfigView.getResilience());
        system.put("pricing", gatewayConfigView.getPricing());
        system.put("operational", gatewayConfigView.getOperational());

        return new SnapshotResponse(Instant.now(), providers, routes, scenes, clients, system);
    }
}
