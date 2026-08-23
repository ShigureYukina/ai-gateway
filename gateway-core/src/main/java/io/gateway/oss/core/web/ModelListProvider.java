package io.gateway.oss.core.web;

import java.util.List;

/**
 * SPI for building the model list exposed at {@code GET /v1/models}.
 * <p>Implemented in {@code gateway-admin} by {@code ModelListService} (scene-based model groups).
 * Optional — when no implementation is available the endpoint returns an empty list.</p>
 */
public interface ModelListProvider {

    List<ModelsController.ModelObject> buildModels(String providerFilter, String modelFilter);

    /**
     * Whether this provider has data to return. Used to distinguish "no provider configured"
     * from "provider has no matching models".
     */
    boolean hasData();
}
