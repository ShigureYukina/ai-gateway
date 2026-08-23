package io.gateway.oss.core.contract;

import io.gateway.oss.core.config.ModelPricing;

import java.util.Map;

public interface PricingConfigView {
    ModelPricing getDefault();
    Map<String, ModelPricing> getModels();
    Map<String, String> getExactMatches();
}
