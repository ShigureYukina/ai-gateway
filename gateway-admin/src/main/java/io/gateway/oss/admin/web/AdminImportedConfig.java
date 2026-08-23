package io.gateway.oss.admin.web;

import io.gateway.oss.core.config.ClientConfig;
import io.gateway.oss.core.config.LimitConfig;
import io.gateway.oss.core.config.OperationalConfig;
import io.gateway.oss.core.config.PricingConfig;
import io.gateway.oss.core.config.ProviderConfig;
import io.gateway.oss.core.config.ResilienceConfig;
import io.gateway.oss.core.config.RouteConfig;
import io.gateway.oss.core.config.SceneConfig;

import java.util.Map;

/**
 * 管理端配置导入解析结果。
 */
public record AdminImportedConfig(
        Map<String, ProviderConfig> providers,
        Map<String, RouteConfig> routes,
        Map<String, SceneConfig> scenes,
        Map<String, ClientConfig> clients,
        LimitConfig limitConfig,
        ResilienceConfig resilienceConfig,
        PricingConfig pricingConfig,
        OperationalConfig operationalConfig,
        int importedCount) {
}
