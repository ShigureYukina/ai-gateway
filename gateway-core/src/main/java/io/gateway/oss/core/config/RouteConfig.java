package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.RouteConfigView;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Min;

import java.util.ArrayList;
import java.util.List;

public class RouteConfig implements RouteConfigView {

    private String provider;
    private String upstreamModel;
    private List<String> upstreamModels = new ArrayList<>();
    private String scene;
    private String strategy = "round-robin";
    private List<String> fallbackRoutes = new ArrayList<>();
    @Min(1)
    private int weight = 1;
    private boolean enabled = true;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getUpstreamModel() {
        return upstreamModel;
    }

    public void setUpstreamModel(String upstreamModel) {
        this.upstreamModel = upstreamModel;
    }

    public List<String> getUpstreamModels() {
        return upstreamModels;
    }

    public void setUpstreamModels(List<String> upstreamModels) {
        this.upstreamModels = upstreamModels;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public List<String> getFallbackRoutes() {
        return fallbackRoutes;
    }

    public void setFallbackRoutes(List<String> fallbackRoutes) {
        this.fallbackRoutes = fallbackRoutes;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @JsonIgnore
    public boolean isConcreteRoute() {
        return hasText(provider) && (hasText(upstreamModel) || !upstreamModels.isEmpty());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
