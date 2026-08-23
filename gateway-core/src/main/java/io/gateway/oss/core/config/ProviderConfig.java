package io.gateway.oss.core.config;

import io.gateway.oss.core.contract.ProviderConfigView;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ProviderConfig implements ProviderConfigView {

    private String type = "openai-compatible";
    @NotBlank
    private String baseUrl;
    private String apiKey;
    private List<String> keys = new ArrayList<>();
    private List<Integer> keyWeights = new ArrayList<>();
    private Duration timeout = Duration.ofSeconds(30);
    private boolean enabled = true;
    private List<String> models = new ArrayList<>();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public List<String> getKeys() {
        return keys;
    }

    public void setKeys(List<String> keys) {
        this.keys = keys;
    }

    public List<Integer> getKeyWeights() {
        return keyWeights;
    }

    public void setKeyWeights(List<Integer> keyWeights) {
        this.keyWeights = keyWeights;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }

    @AssertTrue(message = "Either api-key or keys must be configured")
    public boolean hasApiKeyConfigured() {
        if (hasText(apiKey)) {
            return true;
        }
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        for (String key : keys) {
            if (hasText(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
