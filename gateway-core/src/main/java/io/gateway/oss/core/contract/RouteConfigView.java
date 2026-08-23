package io.gateway.oss.core.contract;

import java.util.List;

/**
 * Route 只读配置视图。
 * <p>
 * 该接口只描述路由读取边界，供后续 core/admin 间按契约访问路由配置时复用。
 * </p>
 */
public interface RouteConfigView {

    String getProvider();

    String getUpstreamModel();

    List<String> getUpstreamModels();

    String getScene();

    String getStrategy();

    List<String> getFallbackRoutes();

    int getWeight();

    boolean isEnabled();
}
