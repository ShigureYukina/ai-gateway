package io.gateway.oss.core.contract;

/**
 * 网关配置聚合只读视图。
 * <p>
 * 该接口用于向读取侧暴露稳定的配置访问边界，
 * 后续可逐步替代直接依赖具体 `GatewayProperties` 实现的调用点。
 * </p>
 */
public interface GatewayConfigView extends SystemConfigView, PricingPublicationConfigView,
        ProviderCatalogView, RouteCatalogView, SceneCatalogView, ClientCatalogView {
}
