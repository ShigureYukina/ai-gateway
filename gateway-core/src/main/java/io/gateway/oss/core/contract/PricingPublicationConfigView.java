package io.gateway.oss.core.contract;

import java.util.Map;

/**
 * pricing / model-publication 读取侧的窄配置视图。
 * <p>
 * 该接口仅暴露当前这簇读取逻辑所需的最小配置面，
 * 不扩展到其他读取场景，也不承载写入职责。
 * </p>
 */
public interface PricingPublicationConfigView {

    PricingConfigView getPricing();

    Map<String, ? extends ProviderConfigView> getProviders();

    Map<String, ? extends RouteConfigView> getRoutes();

    Map<String, ? extends SceneConfigView> getScenes();
}
