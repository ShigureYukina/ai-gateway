package io.gateway.oss.core.contract;

import java.util.List;

/**
 * Scene 只读配置视图。
 * <p>
 * 该接口聚焦场景到主路由/兜底路由的读取，不承载动态写入逻辑。
 * </p>
 */
public interface SceneConfigView {

    String getPrimaryRoute();

    List<String> getFallbackRoutes();
}
