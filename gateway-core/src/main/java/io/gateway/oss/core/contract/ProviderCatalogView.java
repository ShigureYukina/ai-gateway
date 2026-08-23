package io.gateway.oss.core.contract;

import java.util.Map;

/**
 * Provider 子域专用只读视图。
 */
public interface ProviderCatalogView {

    Map<String, ? extends ProviderConfigView> getProviders();
}
