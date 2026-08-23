package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.SceneConfigView;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

public class SceneConfig implements SceneConfigView {

    @NotBlank
    private String primaryRoute;
    private List<String> fallbackRoutes = new ArrayList<>();

    public String getPrimaryRoute() {
        return primaryRoute;
    }

    public void setPrimaryRoute(String primaryRoute) {
        this.primaryRoute = primaryRoute;
    }

    public List<String> getFallbackRoutes() {
        return fallbackRoutes;
    }

    public void setFallbackRoutes(List<String> fallbackRoutes) {
        this.fallbackRoutes = fallbackRoutes;
    }
}
