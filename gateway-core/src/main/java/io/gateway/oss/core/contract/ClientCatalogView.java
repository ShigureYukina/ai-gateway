package io.gateway.oss.core.contract;

import java.util.Map;

/**
 * Client 子域专用只读视图。
 */
public interface ClientCatalogView {

    Map<String, ? extends ClientConfigView> getClients();
}
