package io.gateway.oss.core.contract;

import java.util.Map;

/**
 * Scene 子域专用只读视图。
 */
public interface SceneCatalogView {

    Map<String, ? extends SceneConfigView> getScenes();
}
