package io.gateway.oss.core.contract;

import java.util.Map;

/**
 * Route 子域专用只读视图。
 */
public interface RouteCatalogView {

    Map<String, ? extends RouteConfigView> getRoutes();
}
