package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.PricingConfigView;

import java.util.HashMap;
import java.util.Map;

public class PricingConfig implements PricingConfigView {

    private ModelPricing defaultPricing = new ModelPricing();
    private Map<String, ModelPricing> models = new HashMap<>();
    private Map<String, String> exactMatches = new HashMap<>();

    public ModelPricing getDefault() {
        return defaultPricing;
    }

    public void setDefault(ModelPricing defaultPricing) {
        this.defaultPricing = defaultPricing;
    }

    public Map<String, ModelPricing> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelPricing> models) {
        this.models = models;
    }

    public Map<String, String> getExactMatches() {
        return exactMatches;
    }

    public void setExactMatches(Map<String, String> exactMatches) {
        this.exactMatches = exactMatches;
    }
}
