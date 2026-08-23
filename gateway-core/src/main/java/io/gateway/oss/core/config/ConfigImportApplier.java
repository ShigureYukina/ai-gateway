package io.gateway.oss.core.config;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 将已校验的导入配置应用到动态配置存储。
 * <p>
 * 归属 gateway-core 层，避免 gateway-admin 直接依赖 DynamicConfigService。
 * </p>
 */
public class ConfigImportApplier {

    private final DynamicConfigService dynamicConfigService;

    public ConfigImportApplier(DynamicConfigService dynamicConfigService) {
        this.dynamicConfigService = dynamicConfigService;
    }

    public Mono<Void> apply(Map<String, ProviderConfig> providers,
                            Map<String, RouteConfig> routes,
                            Map<String, SceneConfig> scenes,
                            Map<String, ClientConfig> clients,
                            LimitConfig limitConfig,
                            ResilienceConfig resilienceConfig,
                            PricingConfig pricingConfig,
                            OperationalConfig operationalConfig) {
        return dynamicConfigService.applyImportedConfig(
                providers,
                routes,
                scenes,
                clients,
                limitConfig,
                resilienceConfig,
                pricingConfig,
                operationalConfig);
    }
}
